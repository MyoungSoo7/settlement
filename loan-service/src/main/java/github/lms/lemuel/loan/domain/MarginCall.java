package github.lms.lemuel.loan.domain;

import github.lms.lemuel.common.money.Money;
import github.lms.lemuel.loan.domain.exception.InvalidLoanStateException;
import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 마진콜 엔티티 — 담보유지비율 미달로 추가담보를 요구한 사실과 그 결말. 순수 POJO.
 *
 * <p>대출 1건에 <b>여러 번</b> 발생할 수 있어 대출 애그리거트가 아니라 독립 엔티티다(시가가 오르내리며
 * 반복되고, 각 발생이 따로 해소·이관된다). 요구액은 발생 시점 값을 보존한다 — 사후에 시가가 회복돼도
 * "그때 얼마가 부족했는지"는 바뀌지 않아야 조치의 근거가 남는다.
 *
 * <p>강제 처분 자체는 여기서 하지 않는다 — 이 엔티티는 <b>ESCALATED 로 이관 사실만</b> 남기고,
 * 실제 처분·대위변제는 대출 상태머신(기한이익상실 이후)의 몫이다. 두 생명주기를 섞으면 마진콜이
 * 대출 상태를 직접 바꾸는 결합이 생긴다.
 */
public class MarginCall {

    private final Long id;
    private final Long loanId;
    private final Long collateralId;
    private final BigDecimal requiredAmount;
    private MarginCallStatus status;
    private final LocalDateTime openedAt;
    private LocalDateTime closedAt;

    private MarginCall(Long id, Long loanId, Long collateralId, BigDecimal requiredAmount,
                       MarginCallStatus status, LocalDateTime openedAt, LocalDateTime closedAt) {
        this.id = id;
        this.loanId = loanId;
        this.collateralId = collateralId;
        this.requiredAmount = requiredAmount;
        this.status = status;
        this.openedAt = openedAt;
        this.closedAt = closedAt;
    }

    /**
     * 마진콜 발생.
     *
     * @param requiredAmount 유지비율 회복에 필요한 추가담보액(양수) — 0 이하면 담보가 충분하다는
     *                       뜻이므로 마진콜 자체가 논리 오류다
     */
    public static MarginCall open(Long loanId, Long collateralId, BigDecimal requiredAmount,
                                  LocalDateTime openedAt) {
        if (loanId == null || collateralId == null) {
            throw new LoanInvariantViolationException("마진콜은 대출·담보 식별자가 필수입니다");
        }
        if (requiredAmount == null || requiredAmount.signum() <= 0) {
            throw new LoanInvariantViolationException(
                    "추가담보 요구액은 양수여야 합니다(담보 충분 시 마진콜 대상 아님): " + requiredAmount);
        }
        if (openedAt == null) {
            throw new LoanInvariantViolationException("마진콜 발생 시각은 필수입니다");
        }
        return new MarginCall(null, loanId, collateralId, Money.of(requiredAmount).toBigDecimal(),
                MarginCallStatus.OPEN, openedAt, null);
    }

    /** 영속화된 상태를 재구성(리포지토리 전용). */
    public static MarginCall reconstitute(Long id, Long loanId, Long collateralId,
                                          BigDecimal requiredAmount, MarginCallStatus status,
                                          LocalDateTime openedAt, LocalDateTime closedAt) {
        return new MarginCall(id, loanId, collateralId, requiredAmount, status, openedAt, closedAt);
    }

    /** 담보 보충·일부 상환으로 유지비율이 회복됨. */
    public void resolve(LocalDateTime closedAt) {
        close(MarginCallStatus.RESOLVED, closedAt);
    }

    /** 미해소 — 강제 처분 절차로 이관됨. */
    public void escalate(LocalDateTime closedAt) {
        close(MarginCallStatus.ESCALATED, closedAt);
    }

    /** 아직 해소되지 않은 마진콜인지 — 중복 발생 방지 가드가 참조한다. */
    public boolean isOpen() {
        return status == MarginCallStatus.OPEN;
    }

    private void close(MarginCallStatus target, LocalDateTime at) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidLoanStateException(status, target);
        }
        if (at == null) {
            throw new LoanInvariantViolationException("마진콜 종료 시각은 필수입니다");
        }
        if (at.isBefore(openedAt)) {
            throw new LoanInvariantViolationException(
                    "마진콜 종료 시각은 발생 시각보다 앞설 수 없습니다: " + at + " < " + openedAt);
        }
        this.status = target;
        this.closedAt = at;
    }

    public Long getId() { return id; }
    public Long getLoanId() { return loanId; }
    public Long getCollateralId() { return collateralId; }
    public BigDecimal getRequiredAmount() { return requiredAmount; }
    public MarginCallStatus getStatus() { return status; }
    public LocalDateTime getOpenedAt() { return openedAt; }
    public LocalDateTime getClosedAt() { return closedAt; }
}
