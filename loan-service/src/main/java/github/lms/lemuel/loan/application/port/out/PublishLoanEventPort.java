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

    /** 선정산 대출이 실행되어 셀러에게 선지급된 사실을 알린다. */
    void publishDisbursementRequested(LoanAdvance loan);

    /** 정산건에서 대출 상환액(deducted)이 차감 적용된 사실을 알린다. */
    void publishRepaymentApplied(long settlementId, long sellerId, BigDecimal deducted);
}
