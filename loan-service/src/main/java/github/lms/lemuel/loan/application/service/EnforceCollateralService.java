package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.EnforceCollateralUseCase;
import github.lms.lemuel.loan.application.port.out.AppendLedgerPort;
import github.lms.lemuel.loan.application.port.out.LoadSecuredLoanPort;
import github.lms.lemuel.loan.application.port.out.PublishSecuredLoanEventPort;
import github.lms.lemuel.loan.application.port.out.SaveSecuredLoanPort;
import github.lms.lemuel.loan.domain.Collateral;
import github.lms.lemuel.loan.domain.CollateralStatus;
import github.lms.lemuel.loan.domain.LoanLedgerEntry;
import github.lms.lemuel.loan.domain.SecuredLoan;
import github.lms.lemuel.loan.domain.SecuredLoanPolicy;
import github.lms.lemuel.loan.domain.SecuredLoanStatus;
import github.lms.lemuel.loan.domain.exception.SecuredLoanNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;

/**
 * 담보 실행 — 처분 · 대위변제 · 상각.
 *
 * <p>기한이익상실(DEFAULTED) 이후에만 실행된다. 그 전에는 아직 회수 가능한 채권이라 상각 전이가
 * 도메인 상태머신에서 막힌다.
 *
 * <p><b>손실 계정을 경로별로 나눈다</b> — 처분 부족분은 {@code COLLATERAL_DISPOSAL_LOSS}(담보물을
 * 실제로 처분한 결과), 대위변제 미보증분은 {@code BAD_DEBT_EXPENSE}(처분 없이 회수 불능 확정)다.
 * 두 전표를 같이 쓰면 손실이 이중 인식되므로 경로마다 하나만 기표한다.
 *
 * <p>실행은 <b>비관적 락</b>으로 조회한다 — 동시 처분 요청이 들어와도 회수·상각 전표가 중복되지 않는다.
 */
@Service
public class EnforceCollateralService implements EnforceCollateralUseCase {

    private final LoadSecuredLoanPort loadSecuredLoanPort;
    private final SaveSecuredLoanPort saveSecuredLoanPort;
    private final AppendLedgerPort appendLedgerPort;
    private final PublishSecuredLoanEventPort publishSecuredLoanEventPort;
    private final BigDecimal baseRatePercent;
    private final BigDecimal realEstateLtvRatio;
    private final Clock clock;

    public EnforceCollateralService(LoadSecuredLoanPort loadSecuredLoanPort,
                                    SaveSecuredLoanPort saveSecuredLoanPort,
                                    AppendLedgerPort appendLedgerPort,
                                    PublishSecuredLoanEventPort publishSecuredLoanEventPort,
                                    @Value("${app.loan.secured.base-rate-percent:3.5}")
                                    BigDecimal baseRatePercent,
                                    @Value("${app.loan.secured.real-estate-ltv:0.70}")
                                    BigDecimal realEstateLtvRatio,
                                    Clock clock) {
        this.loadSecuredLoanPort = loadSecuredLoanPort;
        this.saveSecuredLoanPort = saveSecuredLoanPort;
        this.appendLedgerPort = appendLedgerPort;
        this.publishSecuredLoanEventPort = publishSecuredLoanEventPort;
        this.baseRatePercent = baseRatePercent;
        this.realEstateLtvRatio = realEstateLtvRatio;
        this.clock = clock;
    }

    @Override
    @Transactional
    public EnforcementResult dispose(Long loanId, BigDecimal proceeds) {
        if (proceeds == null || proceeds.signum() <= 0) {
            throw new IllegalArgumentException("처분 매각대금은 양수여야 합니다: " + proceeds);
        }
        SecuredLoan loan = require(loanId);
        Collateral collateral = requireEnforceable(loan);
        if (!collateral.getType().supportsDisposal()) {
            throw new IllegalArgumentException(
                    "처분 대상이 아닌 담보 유형입니다(보증부는 대위변제 경로): " + collateral.getType());
        }

        BigDecimal outstandingBefore = loan.getOutstanding();
        BigDecimal recovered = loan.repay(proceeds);   // 잔액을 넘어서 차감되지 않는다(clamp)
        appendLedgerPort.append(LoanLedgerEntry.securedCollateralDisposal(loan.getId(), recovered));
        // 원금 감소는 건별로 알린다 — 완제 이벤트만으로는 계정계가 기중 잔액을 모른다(#183).
        if (recovered.signum() > 0) {
            publishSecuredLoanEventPort.publishPrincipalRepaid(loan, recovered, "COLLATERAL_DISPOSAL");
        }

        BigDecimal surplus = proceeds.subtract(outstandingBefore);
        if (surplus.signum() > 0) {
            appendLedgerPort.append(LoanLedgerEntry.securedDisposalSurplus(loan.getId(), surplus));
        } else {
            surplus = BigDecimal.ZERO;
        }

        // 부족분은 처분손실로 확정한다(대손충당금 아님 — 담보를 실제로 처분한 결과다).
        BigDecimal writtenOff = BigDecimal.ZERO;
        if (loan.getStatus() != SecuredLoanStatus.REPAID) {
            writtenOff = loan.writeOff();
            appendLedgerPort.append(LoanLedgerEntry.securedDisposalShortfall(loan.getId(), writtenOff));
        }

        return finish(loan, collateral, recovered, surplus, writtenOff);
    }

    @Override
    @Transactional
    public EnforcementResult subrogate(Long loanId) {
        SecuredLoan loan = require(loanId);
        Collateral collateral = requireEnforceable(loan);
        if (!collateral.getType().supportsSubrogation()) {
            throw new IllegalArgumentException(
                    "대위변제 대상이 아닌 담보 유형입니다(처분 경로): " + collateral.getType());
        }

        SecuredLoanPolicy policy = new SecuredLoanPolicy(baseRatePercent, realEstateLtvRatio);
        BigDecimal claim = policy.subrogationRecovery(loan.getOutstanding());
        BigDecimal recovered = loan.repay(claim);
        appendLedgerPort.append(LoanLedgerEntry.securedSubrogation(loan.getId(), recovered));
        // 원금 감소는 건별로 알린다 — 완제 이벤트만으로는 계정계가 기중 잔액을 모른다(#183).
        if (recovered.signum() > 0) {
            publishSecuredLoanEventPort.publishPrincipalRepaid(loan, recovered, "SUBROGATION");
        }

        // 미보증분은 처분 없이 회수 불능이 확정된 몫이라 대손으로 인식한다.
        BigDecimal writtenOff = BigDecimal.ZERO;
        if (loan.getStatus() != SecuredLoanStatus.REPAID) {
            writtenOff = loan.writeOff();
            appendLedgerPort.append(LoanLedgerEntry.securedWriteOff(loan.getId(), writtenOff));
        }

        return finish(loan, collateral, recovered, BigDecimal.ZERO, writtenOff);
    }

    /** 실행이 끝나면 담보를 말소하고 대출을 저장한다. */
    private EnforcementResult finish(SecuredLoan loan, Collateral collateral, BigDecimal recovered,
                                     BigDecimal surplus, BigDecimal writtenOff) {
        if (collateral.getStatus() != CollateralStatus.RELEASED) {
            collateral.release();
            saveSecuredLoanPort.saveCollateral(collateral);
        }
        SecuredLoan saved = saveSecuredLoanPort.save(loan);
        return new EnforcementResult(saved.getId(), recovered, surplus, writtenOff,
                saved.getStatus().name());
    }

    /** 실행 가능 상태·담보 유무 확인. 전이 가능 여부 판정은 도메인 상태머신이 하지만, 담보 실행은
     *  DEFAULTED 를 명시 전제로 하므로 진입 시점에 확인해 오해 없는 오류를 던진다. */
    private Collateral requireEnforceable(SecuredLoan loan) {
        if (loan.getStatus() != SecuredLoanStatus.DEFAULTED) {
            throw new IllegalStateException(
                    "담보 실행은 기한이익상실(DEFAULTED) 이후에만 가능합니다. 현재=" + loan.getStatus());
        }
        Collateral collateral = loan.getCollateral();
        if (collateral == null) {
            throw new IllegalArgumentException("담보 없는 대출은 담보 실행 대상이 아닙니다");
        }
        return collateral;
    }

    private SecuredLoan require(Long loanId) {
        return loadSecuredLoanPort.findByIdForUpdate(loanId)
                .orElseThrow(() -> new SecuredLoanNotFoundException(
                        "담보대출을 찾을 수 없습니다. loanId=" + loanId));
    }
}
