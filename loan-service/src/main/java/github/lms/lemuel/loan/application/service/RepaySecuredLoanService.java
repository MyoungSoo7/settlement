package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.RepaySecuredLoanUseCase;
import github.lms.lemuel.loan.application.port.out.AppendLedgerPort;
import github.lms.lemuel.loan.application.port.out.LoadSecuredLoanPort;
import github.lms.lemuel.loan.application.port.out.LoanMetricsPort;
import github.lms.lemuel.loan.application.port.out.PublishSecuredLoanEventPort;
import github.lms.lemuel.loan.application.port.out.SaveSecuredLoanPort;
import github.lms.lemuel.loan.domain.Collateral;
import github.lms.lemuel.loan.domain.CollateralStatus;
import github.lms.lemuel.loan.domain.LoanLedgerEntry;
import github.lms.lemuel.loan.domain.SecuredLoan;
import github.lms.lemuel.loan.domain.SecuredLoanStatus;
import github.lms.lemuel.loan.domain.exception.SecuredLoanNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 담보/개인신용 대출 회차 상환.
 *
 * <p>원금과 이자를 <b>분리해 기표</b>한다 — 원금은 대출채권 감소(차 CASH / 대 LOAN_RECEIVABLE),
 * 이자는 수익 인식(차 CASH / 대 FEE_INCOME)이라 계정 흐름이 다르다. 합계만 받아 응용 계층이 임의로
 * 쪼개면 상환표와 원장이 어긋나므로 호출 측이 두 값을 명시한다.
 *
 * <p>이자 0 회차는 이자 전표를 남기지 않는다 — 원장 전표는 금액이 양수여야 한다는 불변식
 * ({@code LedgerInvariants})이 있어 0원 전표는 애초에 만들어지지 않는다.
 *
 * <p><b>소유권 대조(IDOR 방지)</b>: 대출 식별자는 요청에서 오지만 상환 주체는 JWT 에서 파생된 값이며,
 * 둘이 불일치하면 403 이다. 웹 어댑터에도 같은 검사가 있지만 서비스에서 한 번 더 막아 다른 인바운드
 * 경로(배치·내부 호출)가 우회하지 못하게 한다.
 */
@Service
public class RepaySecuredLoanService implements RepaySecuredLoanUseCase {

    private final LoadSecuredLoanPort loadSecuredLoanPort;
    private final SaveSecuredLoanPort saveSecuredLoanPort;
    private final AppendLedgerPort appendLedgerPort;
    private final PublishSecuredLoanEventPort publishSecuredLoanEventPort;
    private final LoanMetricsPort loanMetricsPort;

    public RepaySecuredLoanService(LoadSecuredLoanPort loadSecuredLoanPort,
                                   SaveSecuredLoanPort saveSecuredLoanPort,
                                   AppendLedgerPort appendLedgerPort,
                                   PublishSecuredLoanEventPort publishSecuredLoanEventPort,
                                   LoanMetricsPort loanMetricsPort) {
        this.loadSecuredLoanPort = loadSecuredLoanPort;
        this.saveSecuredLoanPort = saveSecuredLoanPort;
        this.appendLedgerPort = appendLedgerPort;
        this.publishSecuredLoanEventPort = publishSecuredLoanEventPort;
        this.loanMetricsPort = loanMetricsPort;
    }

    @Override
    @Transactional
    public SecuredLoan repay(Long loanId, Long requesterUserId,
                             BigDecimal principalPortion, BigDecimal interestPortion) {
        if (interestPortion == null || interestPortion.signum() < 0) {
            throw new IllegalArgumentException("이자 상환분은 음수일 수 없습니다: " + interestPortion);
        }
        // 동시 상환 요청을 직렬화한다 — 잔액 이중 차감·전표 중복 방어.
        SecuredLoan loan = loadSecuredLoanPort.findByIdForUpdate(loanId)
                .orElseThrow(() -> new SecuredLoanNotFoundException(
                        "담보대출을 찾을 수 없습니다. loanId=" + loanId));
        requireOwner(loan, requesterUserId);

        BigDecimal deducted = loan.repay(principalPortion);
        releaseCollateralIfSettled(loan);
        SecuredLoan saved = saveSecuredLoanPort.save(loan);

        appendLedgerPort.append(LoanLedgerEntry.securedPrincipalRepayment(saved.getId(), deducted));
        if (interestPortion.signum() > 0) {
            appendLedgerPort.append(LoanLedgerEntry.securedInterestIncome(saved.getId(), interestPortion));
        }
        // 원금 감소는 건별로 알린다 — 완제 이벤트만으로는 계정계가 기중 잔액을 모른다(#183).
        if (deducted.signum() > 0) {
            publishSecuredLoanEventPort.publishPrincipalRepaid(saved, deducted, "INSTALLMENT");
        }
        if (saved.getStatus() == SecuredLoanStatus.REPAID) {
            // 회차 상환 완제에는 중도상환수수료가 없다 — 수수료는 prepay 경로에서만 부과된다.
            publishSecuredLoanEventPort.publishRepaid(saved, interestPortion, BigDecimal.ZERO);
        }
        loanMetricsPort.securedRepaid(deducted);
        return saved;
    }

    /** 완제되면 담보를 말소한다 — 상환이 끝났는데 담보가 유효로 남아 있으면 안 된다. */
    private void releaseCollateralIfSettled(SecuredLoan loan) {
        if (loan.getStatus() != SecuredLoanStatus.REPAID) {
            return;
        }
        Collateral collateral = loan.getCollateral();
        if (collateral != null && collateral.getStatus() != CollateralStatus.RELEASED) {
            collateral.release();
            saveSecuredLoanPort.saveCollateral(collateral);
        }
    }

    private static void requireOwner(SecuredLoan loan, Long requesterUserId) {
        if (requesterUserId == null || !requesterUserId.equals(loan.getBorrower().userId())) {
            throw new AccessDeniedException("본인 소유가 아닌 대출입니다. loanId=" + loan.getId());
        }
    }
}
