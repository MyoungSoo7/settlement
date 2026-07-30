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

    /**
     * 원금이 실제로 줄어들 때마다 발행한다 — 회차 상환·중도상환·담보 처분 회수·대위변제 회수.
     *
     * <p>완제 이벤트({@link #publishRepaid})만으로는 계정계가 기중 잔액을 알 수 없다. 완제 전까지
     * 차주의 대출채권이 최초 계약 원금 그대로 남아 시산·실체화 잔액이 계속 틀린 값이 된다.
     * 그래서 감소분을 건별로 알린다(금액은 실제 차감액).
     *
     * @param principalRepaid 이번에 실제로 차감된 원금(잔액 clamp 이후 값)
     * @param reason          감소 사유 — INSTALLMENT | PREPAYMENT | COLLATERAL_DISPOSAL | SUBROGATION
     */
    void publishPrincipalRepaid(SecuredLoan loan, BigDecimal principalRepaid, String reason);
}
