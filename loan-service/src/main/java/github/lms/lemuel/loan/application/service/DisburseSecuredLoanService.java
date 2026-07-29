package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.DisburseSecuredLoanUseCase;
import github.lms.lemuel.loan.application.port.out.AppendLedgerPort;
import github.lms.lemuel.loan.application.port.out.LoadSecuredLoanPort;
import github.lms.lemuel.loan.application.port.out.PublishSecuredLoanEventPort;
import github.lms.lemuel.loan.application.port.out.SaveSecuredLoanPort;
import github.lms.lemuel.loan.domain.Collateral;
import github.lms.lemuel.loan.domain.LoanLedgerEntry;
import github.lms.lemuel.loan.domain.SecuredLoan;
import github.lms.lemuel.loan.domain.exception.SecuredLoanNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 담보/개인신용 대출 승인·거절·실행.
 *
 * <p>담보의 생명주기를 대출 심사 결과에 맞춰 전이시킨다 — 승인이면 유효화(ACTIVE), 거절이면
 * 말소(RELEASED). 담보를 유효화하지 않으면 도메인이 실행을 거부하므로, 승인 없이 돈이 나가는 경로는
 * 상태머신 차원에서 닫혀 있다.
 *
 * <p>실행은 <b>비관적 락</b>으로 조회한다 — 동시 실행 요청이 들어와도 전표·이벤트가 중복 발생하지 않는다.
 * 도메인 저장·전표·Outbox 기록이 한 트랜잭션이라 원자성이 보장된다.
 */
@Service
public class DisburseSecuredLoanService implements DisburseSecuredLoanUseCase {

    private final LoadSecuredLoanPort loadSecuredLoanPort;
    private final SaveSecuredLoanPort saveSecuredLoanPort;
    private final AppendLedgerPort appendLedgerPort;
    private final PublishSecuredLoanEventPort publishSecuredLoanEventPort;

    public DisburseSecuredLoanService(LoadSecuredLoanPort loadSecuredLoanPort,
                                      SaveSecuredLoanPort saveSecuredLoanPort,
                                      AppendLedgerPort appendLedgerPort,
                                      PublishSecuredLoanEventPort publishSecuredLoanEventPort) {
        this.loadSecuredLoanPort = loadSecuredLoanPort;
        this.saveSecuredLoanPort = saveSecuredLoanPort;
        this.appendLedgerPort = appendLedgerPort;
        this.publishSecuredLoanEventPort = publishSecuredLoanEventPort;
    }

    @Override
    @Transactional
    public SecuredLoan approve(Long loanId) {
        SecuredLoan loan = require(loanId);
        loan.approve();
        activateCollateral(loan);
        return saveSecuredLoanPort.save(loan);
    }

    @Override
    @Transactional
    public SecuredLoan reject(Long loanId) {
        SecuredLoan loan = require(loanId);
        loan.reject();
        releaseCollateral(loan);
        return saveSecuredLoanPort.save(loan);
    }

    @Override
    @Transactional
    public SecuredLoan disburse(Long loanId) {
        SecuredLoan loan = require(loanId);
        loan.disburse();
        SecuredLoan saved = saveSecuredLoanPort.save(loan);

        // 복식부기: 대출채권/현금 (amount = 원금). 이자는 회차 수취 시점에 인식하므로 여기서는 없다.
        appendLedgerPort.append(
                LoanLedgerEntry.securedDisbursement(saved.getId(), saved.getPrincipal()));
        publishSecuredLoanEventPort.publishDisbursed(saved);
        return saved;
    }

    /** 담보형이면 담보를 유효화한다(무담보 상품은 no-op). */
    private void activateCollateral(SecuredLoan loan) {
        Collateral collateral = loan.getCollateral();
        if (collateral != null) {
            collateral.activate();
            saveSecuredLoanPort.saveCollateral(collateral);
        }
    }

    /** 거절 시 설정된 담보를 말소한다 — 설정만 남은 담보가 방치되지 않게 한다. */
    private void releaseCollateral(SecuredLoan loan) {
        Collateral collateral = loan.getCollateral();
        if (collateral != null) {
            collateral.release();
            saveSecuredLoanPort.saveCollateral(collateral);
        }
    }

    // 실행·전이는 전부 락 조회로 시작한다 — 동시 요청 직렬화가 이중지급 방어의 1차선이다.
    private SecuredLoan require(Long loanId) {
        return loadSecuredLoanPort.findByIdForUpdate(loanId)
                .orElseThrow(() -> new SecuredLoanNotFoundException("담보대출을 찾을 수 없습니다. loanId=" + loanId));
    }
}
