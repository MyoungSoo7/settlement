package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.DisburseCorporateLoanUseCase;
import github.lms.lemuel.loan.application.port.out.AppendLedgerPort;
import github.lms.lemuel.loan.application.port.out.LoadCorporateLoanPort;
import github.lms.lemuel.loan.application.port.out.PublishCorporateLoanEventPort;
import github.lms.lemuel.loan.application.port.out.SaveCorporateLoanPort;
import github.lms.lemuel.loan.domain.CorporateLoan;
import github.lms.lemuel.loan.domain.exception.CorporateLoanNotFoundException;
import github.lms.lemuel.loan.domain.LoanLedgerEntry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기업 신용대출 실행(지급). 승인 → 실행(미상환잔액=원금+수수료) → 복식부기 전표 2건(선지급 + 수수료 인식)
 * → {@code CorporateLoanDisbursed} 이벤트를 Outbox 에 기록한다. 도메인 저장·전표·이벤트가 한 트랜잭션이라
 * 원자성이 보장된다.
 */
@Service
public class DisburseCorporateLoanService implements DisburseCorporateLoanUseCase {

    private final LoadCorporateLoanPort loadCorporateLoanPort;
    private final SaveCorporateLoanPort saveCorporateLoanPort;
    private final AppendLedgerPort appendLedgerPort;
    private final PublishCorporateLoanEventPort publishCorporateLoanEventPort;

    public DisburseCorporateLoanService(LoadCorporateLoanPort loadCorporateLoanPort,
                                        SaveCorporateLoanPort saveCorporateLoanPort,
                                        AppendLedgerPort appendLedgerPort,
                                        PublishCorporateLoanEventPort publishCorporateLoanEventPort) {
        this.loadCorporateLoanPort = loadCorporateLoanPort;
        this.saveCorporateLoanPort = saveCorporateLoanPort;
        this.appendLedgerPort = appendLedgerPort;
        this.publishCorporateLoanEventPort = publishCorporateLoanEventPort;
    }

    @Override
    @Transactional
    public CorporateLoan disburse(Long loanId) {
        // 비관적 락으로 조회 — 동시 disburse 요청 시 이중지급(전표·이벤트 중복)을 차단한다.
        CorporateLoan loan = loadCorporateLoanPort.findByIdForUpdate(loanId)
                .orElseThrow(() -> new CorporateLoanNotFoundException(
                        "기업대출을 찾을 수 없습니다. loanId=" + loanId));

        loan.approve();
        loan.disburse();
        CorporateLoan saved = saveCorporateLoanPort.save(loan);

        // 복식부기: 선지급(대출채권/현금) + 수수료 인식(미수수익/수수료수익)
        appendLedgerPort.append(LoanLedgerEntry.corporateDisbursement(saved.getId(), saved.getPrincipal()));
        if (saved.getFee().signum() > 0) {
            appendLedgerPort.append(LoanLedgerEntry.corporateFeeAccrual(saved.getId(), saved.getFee()));
        }

        publishCorporateLoanEventPort.publishDisbursed(saved);
        return saved;
    }
}
