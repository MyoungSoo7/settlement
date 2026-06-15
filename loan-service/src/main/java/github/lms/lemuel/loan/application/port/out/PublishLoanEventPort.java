package github.lms.lemuel.loan.application.port.out;

import github.lms.lemuel.loan.domain.LoanAdvance;

import java.math.BigDecimal;

/**
 * loan 도메인 이벤트 발행 아웃바운드 포트 (Transactional Outbox 경유).
 *
 * <p>발행 토픽은 Outbox 폴러가 aggregateType="Loan" + eventType 으로 자동 라우팅한다:
 * <ul>
 *   <li>LoanDisbursementRequested → lemuel.loan.disbursement_requested</li>
 *   <li>LoanRepaymentApplied      → lemuel.loan.repayment_applied</li>
 * </ul>
 */
public interface PublishLoanEventPort {

    /** 선지급 실행 — settlement 가 payout 으로 셀러에게 송금하도록 요청. */
    void publishDisbursementRequested(LoanAdvance loan);

    /** 상환 차감 완료 — settlement 가 순지급액(amount-deducted) 으로 payout 하도록 통지. */
    void publishRepaymentApplied(long settlementId, long sellerId, BigDecimal deducted);
}
