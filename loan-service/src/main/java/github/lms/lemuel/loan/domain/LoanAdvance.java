package github.lms.lemuel.loan.domain;

import github.lms.lemuel.common.money.Money;
import github.lms.lemuel.loan.domain.exception.InvalidLoanStateException;
import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;

import java.math.BigDecimal;

/**
 * 선지급(선정산 대출) 애그리거트 루트. 순수 POJO — 프레임워크 의존 0.
 *
 * <p>상태 전이와 상환 차감의 불변식을 도메인 내부에서 강제한다.
 * 한도/수수료 산정은 {@link github.lms.lemuel.loan.application.service.CreditPolicy} 의 책임이며,
 * 이 애그리거트는 산정된 원금(principal)·수수료(fee)를 받아 미상환잔액(outstanding)을 관리한다.
 */
public class LoanAdvance {

    private Long id;
    private final Long sellerId;
    private final BigDecimal principal;
    private final BigDecimal fee;
    private BigDecimal outstanding;
    private LoanStatus status;

    private LoanAdvance(Long id, Long sellerId, BigDecimal principal, BigDecimal fee,
                        BigDecimal outstanding, LoanStatus status) {
        this.id = id;
        this.sellerId = sellerId;
        this.principal = principal;
        this.fee = fee;
        this.outstanding = outstanding;
        this.status = status;
    }

    /** 신규 대출 신청. 실행 전이므로 미상환잔액은 0. */
    public static LoanAdvance request(Long sellerId, BigDecimal principal, BigDecimal fee) {
        if (principal == null || principal.signum() <= 0) {
            throw new LoanInvariantViolationException("선지급 원금은 양수여야 합니다: " + principal);
        }
        if (fee == null || fee.signum() < 0) {
            throw new LoanInvariantViolationException("수수료는 음수일 수 없습니다: " + fee);
        }
        // 금액은 도메인 진입 시 Money(scale 2, HALF_UP)로 정규화한다 — 저장·계산 표현을 일관화(money-safety).
        BigDecimal normalizedPrincipal = Money.of(principal).toBigDecimal();
        BigDecimal normalizedFee = Money.of(fee).toBigDecimal();
        return new LoanAdvance(null, sellerId, normalizedPrincipal, normalizedFee,
                BigDecimal.ZERO, LoanStatus.REQUESTED);
    }

    /** 영속화된 상태를 재구성(리포지토리 전용). */
    public static LoanAdvance reconstitute(Long id, Long sellerId, BigDecimal principal, BigDecimal fee,
                                           BigDecimal outstanding, LoanStatus status) {
        return new LoanAdvance(id, sellerId, principal, fee, outstanding, status);
    }

    public void approve() {
        requireTransition(LoanStatus.APPROVED);
        this.status = LoanStatus.APPROVED;
    }

    public void reject() {
        requireTransition(LoanStatus.REJECTED);
        this.status = LoanStatus.REJECTED;
    }

    /** 실행(선지급). 미상환잔액 = 원금 + 수수료. */
    public void disburse() {
        requireTransition(LoanStatus.DISBURSED);
        this.outstanding = Money.of(principal).plus(Money.of(fee)).toBigDecimal();
        this.status = LoanStatus.DISBURSED;
    }

    /**
     * 정산 확정 시 차감 상환. 차감액 = min(미상환잔액, 가용 정산금).
     *
     * @param available 이 대출에 충당 가능한 정산금
     * @return 실제 차감된 금액
     */
    public BigDecimal applyRepayment(BigDecimal available) {
        requireTransition(LoanStatus.REPAID);
        if (available == null || available.signum() < 0) {
            throw new LoanInvariantViolationException("상환 가용액은 음수일 수 없습니다: " + available);
        }
        // CorporateLoan.repay 동형 — 차감·잔액 계산을 Money(scale 2, HALF_UP) 로 통일한다.
        Money remaining = Money.of(outstanding);
        Money deducted = remaining.min(Money.of(available));
        remaining = remaining.minus(deducted);
        this.outstanding = remaining.toBigDecimal();
        if (remaining.isZero()) {
            this.status = LoanStatus.REPAID;
        }
        return deducted.toBigDecimal();
    }

    /**
     * 연체 진입. 실행(DISBURSED)된 대출이 상환되지 않아 회수 위험 상태로 전이한다.
     * 미상환잔액이 남아 있어야 연체가 성립한다(잔액 0이면 이미 REPAID 여야 함).
     */
    public void markOverdue() {
        if (outstanding == null || outstanding.signum() <= 0) {
            throw new LoanInvariantViolationException(
                    "미상환잔액이 없는 대출은 연체 처리할 수 없습니다: " + outstanding);
        }
        requireTransition(LoanStatus.OVERDUE);
        this.status = LoanStatus.OVERDUE;
    }

    /**
     * 상각(회수 불능 확정). 연체(OVERDUE)된 대출의 미상환잔액을 대손으로 확정하고 종료 상태로 전이한다.
     * 미상환잔액(=상각 손실액)은 보존해 후속 대손 전표(BAD_DEBT) 산정 근거로 남긴다.
     *
     * @return 상각된 손실액(미상환잔액)
     */
    public BigDecimal writeOff() {
        requireTransition(LoanStatus.WRITTEN_OFF);
        BigDecimal loss = outstanding;
        this.status = LoanStatus.WRITTEN_OFF;
        return loss;
    }

    // 상태 전이 가드 — 허용 전이는 LoanStatus#canTransitionTo 단일 출처에 위임한다.
    private void requireTransition(LoanStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidLoanStateException(status, target);
        }
    }

    public Long getId() { return id; }
    public Long getSellerId() { return sellerId; }
    public BigDecimal getPrincipal() { return principal; }
    public BigDecimal getFee() { return fee; }
    public BigDecimal getOutstanding() { return outstanding; }
    public LoanStatus getStatus() { return status; }
}
