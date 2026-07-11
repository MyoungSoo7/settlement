package github.lms.lemuel.loan.application.port.out;

import github.lms.lemuel.loan.domain.CorporateLoan;

/**
 * 기업 신용대출 이벤트 발행 아웃바운드 포트. 실행 시 {@code CorporateLoanDisbursed} 를
 * Outbox 에 기록해 {@code lemuel.loan.corporate_loan_disbursed} 로 발행한다.
 */
public interface PublishCorporateLoanEventPort {
    void publishDisbursed(CorporateLoan loan);
}
