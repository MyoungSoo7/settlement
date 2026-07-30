package github.lms.lemuel.loan.application.port.out;

import github.lms.lemuel.loan.domain.SecuredLoan;

import java.math.BigDecimal;

/**
 * 담보/개인신용 대출 이벤트 발행 아웃바운드 포트(Outbox 경유).
 *
 * <p>토픽: {@code lemuel.loan.secured_loan_disbursed} · {@code lemuel.loan.secured_loan_repaid}.
 * account-service 가 GL 분개(SECURED_LOAN_RECEIVABLE)로 소비한다 — Phase 2 잔여 이월분 배선.
 */
public interface PublishSecuredLoanEventPort {

    /** 실행 시 발행. */
    void publishDisbursed(SecuredLoan loan);

    /**
     * 완제 시 발행.
     *
     * @param totalInterestPaid 이번 상환에서 수취한 이자(회차 이자 인식과 짝을 이룬다)
     * @param prepaymentFee     완제가 중도상환으로 이뤄진 경우 부과된 중도상환수수료(회차 완제는 0)
     */
    void publishRepaid(SecuredLoan loan, BigDecimal totalInterestPaid, BigDecimal prepaymentFee);
}
