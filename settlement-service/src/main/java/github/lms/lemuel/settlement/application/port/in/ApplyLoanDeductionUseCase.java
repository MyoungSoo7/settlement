package github.lms.lemuel.settlement.application.port.in;

import java.math.BigDecimal;

/**
 * loan 의 상환 차감(LoanRepaymentApplied)을 settlement 측에 반영하는 인바운드 포트.
 *
 * <p>상환 saga 의 settlement 측 <b>종착점</b>: 정산건별 차감액을 기록하고 그 자리에서
 * 즉시지급 Payout 을 생성한다. 지급 생성이 확정 배치가 아니라 여기인 이유는 L-3 —
 * 확정 시점에 금액을 확정하면 뒤늦게 도착하는 대출 차감을 반영할 방법이 없기 때문이다
 * ({@code Payout.amount} 는 final). 차감 순서 정본은 📘settlement-domain-rules 의 "지급액 차감 순서".
 */
public interface ApplyLoanDeductionUseCase {

    /**
     * 정산건의 대출 차감액을 반영하고 즉시지급 Payout 을 생성한다(멱등).
     *
     * <p>멱등은 3중이다: 컨슈머 {@code processed_events}(L2) · 차감기록 PK
     * ({@code settlement_loan_deductions.settlement_id}) · payout {@code (settlement_id, payout_type)} UNIQUE(L3).
     */
    void apply(long settlementId, long sellerId, BigDecimal deducted);
}
