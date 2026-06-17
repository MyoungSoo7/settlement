package github.lms.lemuel.settlement.application.port.in;

import java.math.BigDecimal;

/**
 * loan 의 상환 차감(LoanRepaymentApplied)을 settlement 측에 반영하는 인바운드 포트.
 *
 * <p>상환 saga 의 settlement 측 종착점: 정산건별 차감액을 기록하고, payout 시점에 쓸
 * 순지급액(netAmount - deducted)을 제공한다.
 */
public interface ApplyLoanDeductionUseCase {

    /** 정산건의 대출 차감액을 반영(멱등). */
    void apply(long settlementId, long sellerId, BigDecimal deducted);

    /** 해당 정산의 순지급액 = netAmount - 대출차감액 (0 미만이면 0). payout 트리거가 사용. */
    BigDecimal netPayoutFor(long settlementId);
}
