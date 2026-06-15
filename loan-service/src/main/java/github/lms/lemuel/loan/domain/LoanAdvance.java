package github.lms.lemuel.loan.domain;

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
            throw new IllegalArgumentException("선지급 원금은 양수여야 합니다: " + principal);
        }
        if (fee == null || fee.signum() < 0) {
            throw new IllegalArgumentException("수수료는 음수일 수 없습니다: " + fee);
        }
        return new LoanAdvance(null, sellerId, principal, fee, BigDecimal.ZERO, LoanStatus.REQUESTED);
    }

    /** 영속화된 상태를 재구성(리포지토리 전용). */
    public static LoanAdvance reconstitute(Long id, Long sellerId, BigDecimal principal, BigDecimal fee,
                                           BigDecimal outstanding, LoanStatus status) {
        return new LoanAdvance(id, sellerId, principal, fee, outstanding, status);
    }

    public void approve() {
        requireStatus(LoanStatus.REQUESTED, "승인");
        this.status = LoanStatus.APPROVED;
    }

    public void reject() {
        if (status != LoanStatus.REQUESTED && status != LoanStatus.APPROVED) {
            throw new IllegalStateException("거절은 REQUESTED 또는 APPROVED 에서만 가능합니다. 현재=" + status);
        }
        this.status = LoanStatus.REJECTED;
    }

    /** 실행(선지급). 미상환잔액 = 원금 + 수수료. */
    public void disburse() {
        requireStatus(LoanStatus.APPROVED, "실행");
        this.outstanding = principal.add(fee);
        this.status = LoanStatus.DISBURSED;
    }

    /**
     * 정산 확정 시 차감 상환. 차감액 = min(미상환잔액, 가용 정산금).
     *
     * @param available 이 대출에 충당 가능한 정산금
     * @return 실제 차감된 금액
     */
    public BigDecimal applyRepayment(BigDecimal available) {
        if (status != LoanStatus.DISBURSED) {
            throw new IllegalStateException("상환은 DISBURSED 상태에서만 가능합니다. 현재=" + status);
        }
        if (available == null || available.signum() < 0) {
            throw new IllegalArgumentException("상환 가용액은 음수일 수 없습니다: " + available);
        }
        BigDecimal deducted = outstanding.min(available);
        this.outstanding = outstanding.subtract(deducted);
        if (outstanding.signum() == 0) {
            this.status = LoanStatus.REPAID;
        }
        return deducted;
    }

    private void requireStatus(LoanStatus expected, String action) {
        if (status != expected) {
            throw new IllegalStateException(
                    action + "은(는) " + expected + " 상태에서만 가능합니다. 현재=" + status);
        }
    }

    public Long getId() { return id; }
    public Long getSellerId() { return sellerId; }
    public BigDecimal getPrincipal() { return principal; }
    public BigDecimal getFee() { return fee; }
    public BigDecimal getOutstanding() { return outstanding; }
    public LoanStatus getStatus() { return status; }
}
