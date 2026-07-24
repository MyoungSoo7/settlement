package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.common.audit.application.Auditable;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.loan.application.port.in.ManageLoanCollectionUseCase;
import github.lms.lemuel.loan.application.port.out.AppendLedgerPort;
import github.lms.lemuel.loan.application.port.out.LoadLoanPort;
import github.lms.lemuel.loan.application.port.out.LoanMetricsPort;
import github.lms.lemuel.loan.application.port.out.SaveLoanPort;
import github.lms.lemuel.loan.domain.LoanAdvance;
import github.lms.lemuel.loan.domain.LoanLedgerEntry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 선정산 대출 회수(collection) — 연체 진입·상각(대손 확정)의 loan 측 유스케이스.
 *
 * <p>운영자(ADMIN) 수동 조작 진입점. 상태 전이·불변식(잔액 있는 대출만 연체·상각 가능)은 도메인
 * {@link LoanAdvance} 가 강제하고, 상각 시 미상환잔액을 대손 전표로 인식한다(ledger-invariants).
 */
@Service
public class LoanCollectionService implements ManageLoanCollectionUseCase {

    private final LoadLoanPort loadLoanPort;
    private final SaveLoanPort saveLoanPort;
    private final AppendLedgerPort appendLedgerPort;
    private final LoanMetricsPort loanMetricsPort;

    public LoanCollectionService(LoadLoanPort loadLoanPort,
                                 SaveLoanPort saveLoanPort,
                                 AppendLedgerPort appendLedgerPort,
                                 LoanMetricsPort loanMetricsPort) {
        this.loadLoanPort = loadLoanPort;
        this.saveLoanPort = saveLoanPort;
        this.appendLedgerPort = appendLedgerPort;
        this.loanMetricsPort = loanMetricsPort;
    }

    @Override
    @Transactional
    @Auditable(
            action = AuditAction.LOAN_ADVANCE_OVERDUE,
            resourceType = "LoanAdvance",
            resourceId = "#p0 == null ? null : #p0.toString()",
            detail = "{'loanId': #p0, 'status': #result == null ? null : #result.getStatus().name()}"
    )
    public LoanAdvance markOverdue(Long loanId) {
        LoanAdvance loan = loadLoanPort.load(loanId);
        loan.markOverdue();
        LoanAdvance saved = saveLoanPort.save(loan);
        loanMetricsPort.advanceOverdue();
        return saved;
    }

    @Override
    @Transactional
    @Auditable(
            action = AuditAction.LOAN_ADVANCE_WRITTEN_OFF,
            resourceType = "LoanAdvance",
            resourceId = "#p0 == null ? null : #p0.toString()",
            detail = "{'loanId': #p0, 'status': #result == null ? null : #result.getStatus().name()}"
    )
    public LoanAdvance writeOff(Long loanId) {
        LoanAdvance loan = loadLoanPort.load(loanId);
        BigDecimal loss = loan.writeOff();
        LoanAdvance saved = saveLoanPort.save(loan);

        // 대손 인식: 상각 손실액(미상환잔액)을 비용/충당금 전표로. 잔액 0(불변식상 불가)이면 전표 없음.
        if (loss != null && loss.signum() > 0) {
            appendLedgerPort.append(LoanLedgerEntry.badDebtWriteOff(saved.getId(), loss));
        }
        loanMetricsPort.advanceWrittenOff(loss);
        return saved;
    }
}
