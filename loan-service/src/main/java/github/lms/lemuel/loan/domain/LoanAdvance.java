package github.lms.lemuel.loan.domain;

import github.lms.lemuel.common.money.Money;
import github.lms.lemuel.loan.domain.exception.InvalidLoanStateException;
import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 선지급(선정산 대출) 애그리거트 루트. 순수 POJO — 프레임워크 의존 0.
 *
 * <p>상태 전이와 상환 차감의 불변식을 도메인 내부에서 강제한다.
 * 한도/수수료 산정은 {@link github.lms.lemuel.loan.application.service.CreditPolicy} 의 책임이며,
 * 이 애그리거트는 산정된 원금(principal)·수수료(fee)를 받아 미상환잔액(outstanding)을 관리한다.
 *
 * <p>만기 추적: 신청 시 {@code financingDays}(선지급일수)를 보존하고, 실행 시 {@code disbursedAt} 을 찍어
 * {@code dueAt = disbursedAt + financingDays} 를 확정한다 — 배치 스캐너가 만기 경과분을 자동 연체/상각한다.
 * 시각은 도메인이 만들지 않고 응용 계층이 KST {@link java.time.Clock} 으로 만들어 전달한다(off-by-one 방지).
 */
public class LoanAdvance {

    private Long id;
    private final Long sellerId;
    private final BigDecimal principal;
    private final BigDecimal fee;
    private BigDecimal outstanding;
    private LoanStatus status;
    private final int financingDays;
    private LocalDateTime disbursedAt;
    private LocalDateTime dueAt;

    private LoanAdvance(Long id, Long sellerId, BigDecimal principal, BigDecimal fee,
                        BigDecimal outstanding, LoanStatus status, int financingDays,
                        LocalDateTime disbursedAt, LocalDateTime dueAt) {
        this.id = id;
        this.sellerId = sellerId;
        this.principal = principal;
        this.fee = fee;
        this.outstanding = outstanding;
        this.status = status;
        this.financingDays = financingDays;
        this.disbursedAt = disbursedAt;
        this.dueAt = dueAt;
    }

    /** 신규 대출 신청(선지급일수 미지정 — 구 경로/테스트 호환, 만기 추적 없음). */
    public static LoanAdvance request(Long sellerId, BigDecimal principal, BigDecimal fee) {
        return request(sellerId, principal, fee, 0);
    }

    /**
     * 신규 대출 신청. 실행 전이므로 미상환잔액은 0. {@code financingDays} 는 선지급일수(수수료 산정·만기 계산의
     * 단일 근거)로 보존한다. 실행 시각·만기는 아직 미정(disbursedAt·dueAt=null).
     */
    public static LoanAdvance request(Long sellerId, BigDecimal principal, BigDecimal fee, int financingDays) {
        if (principal == null || principal.signum() <= 0) {
            throw new LoanInvariantViolationException("선지급 원금은 양수여야 합니다: " + principal);
        }
        if (fee == null || fee.signum() < 0) {
            throw new LoanInvariantViolationException("수수료는 음수일 수 없습니다: " + fee);
        }
        if (financingDays < 0) {
            throw new LoanInvariantViolationException("선지급일수는 음수일 수 없습니다: " + financingDays);
        }
        // 금액은 도메인 진입 시 Money(scale 2, HALF_UP)로 정규화한다 — 저장·계산 표현을 일관화(money-safety).
        BigDecimal normalizedPrincipal = Money.of(principal).toBigDecimal();
        BigDecimal normalizedFee = Money.of(fee).toBigDecimal();
        return new LoanAdvance(null, sellerId, normalizedPrincipal, normalizedFee,
                BigDecimal.ZERO, LoanStatus.REQUESTED, financingDays, null, null);
    }

    /** 영속화된 상태를 재구성(리포지토리 전용, 만기 추적 없음 — 구 경로/테스트 호환). */
    public static LoanAdvance reconstitute(Long id, Long sellerId, BigDecimal principal, BigDecimal fee,
                                           BigDecimal outstanding, LoanStatus status) {
        return reconstitute(id, sellerId, principal, fee, outstanding, status, 0, null, null);
    }

    /** 영속화된 상태를 재구성(리포지토리 전용). financingDays·disbursedAt·dueAt 로 만기 추적을 복원한다. */
    public static LoanAdvance reconstitute(Long id, Long sellerId, BigDecimal principal, BigDecimal fee,
                                           BigDecimal outstanding, LoanStatus status, int financingDays,
                                           LocalDateTime disbursedAt, LocalDateTime dueAt) {
        return new LoanAdvance(id, sellerId, principal, fee, outstanding, status,
                financingDays, disbursedAt, dueAt);
    }

    public void approve() {
        requireTransition(LoanStatus.APPROVED);
        this.status = LoanStatus.APPROVED;
    }

    public void reject() {
        requireTransition(LoanStatus.REJECTED);
        this.status = LoanStatus.REJECTED;
    }

    /** 실행(선지급, 만기 추적 없음 — 구 경로/테스트 호환). 미상환잔액 = 원금 + 수수료. */
    public void disburse() {
        disburseInternal();
    }

    /**
     * 실행(선지급). 미상환잔액 = 원금 + 수수료. 실행 시각을 찍고 만기(= asOf + financingDays)를 확정한다.
     *
     * @param asOf 실행 시각(응용 계층이 KST Clock 으로 생성 — 필수)
     */
    public void disburse(LocalDateTime asOf) {
        if (asOf == null) {
            throw new LoanInvariantViolationException("실행 시각(asOf)은 필수입니다");
        }
        disburseInternal();
        this.disbursedAt = asOf;
        this.dueAt = asOf.plusDays(financingDays);
    }

    private void disburseInternal() {
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
    public int getFinancingDays() { return financingDays; }
    public LocalDateTime getDisbursedAt() { return disbursedAt; }
    public LocalDateTime getDueAt() { return dueAt; }
}
