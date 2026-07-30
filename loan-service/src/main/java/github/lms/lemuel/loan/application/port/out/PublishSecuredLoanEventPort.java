package github.lms.lemuel.loan.application.port.out;

import github.lms.lemuel.loan.domain.SecuredLoan;

import java.math.BigDecimal;

/**
 * 담보/개인신용 대출 이벤트 발행 아웃바운드 포트(Outbox 경유).
 *
 * <p>토픽: {@code lemuel.loan.secured_loan_disbursed} · {@code lemuel.loan.secured_loan_repaid}.
 * Phase 1 에는 소비자가 없다 — account-service GL 매핑은 Phase 2 이월이라, 지금은 계약 스키마와
 * 발행만 확정해 두고 소비는 나중에 붙인다.
 */
public interface PublishSecuredLoanEventPort {

    /** 실행 시 발행. */
    void publishDisbursed(SecuredLoan loan);

    /**
     * 완제 시 발행.
     *
     * @param totalInterestPaid 이번 상환에서 수취한 이자(회차 이자 인식과 짝을 이룬다)
     */
    void publishRepaid(SecuredLoan loan, BigDecimal totalInterestPaid);
}
