package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.common.audit.application.Auditable;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.loan.application.port.in.RepayCorporateLoanUseCase;
import github.lms.lemuel.loan.application.port.out.AppendLedgerPort;
import github.lms.lemuel.loan.application.port.out.LoadCorporateLoanPort;
import github.lms.lemuel.loan.application.port.out.LoanMetricsPort;
import github.lms.lemuel.loan.application.port.out.SaveCorporateLoanPort;
import github.lms.lemuel.loan.domain.CorporateLoan;
import github.lms.lemuel.loan.domain.LoanLedgerEntry;
import github.lms.lemuel.loan.domain.exception.CorporateLoanNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 기업 신용대출 상환. 미상환잔액을 차감(clamp)하고, 차감>0 이면 상환 전표(현금/대출채권)를 기록한다.
 * 도메인 저장·전표가 한 트랜잭션이라 원자성이 보장된다.
 *
 * <p>이중상환·경합 방어: {@code findByIdForUpdate} 비관적 락으로 조회해 동시 상환 요청을 직렬화한다
 * (실행 disburse 와 동일한 락 전략). 상환액 검증(양수)·잔액 초과 clamp·상태 전이(DISBURSED→REPAID)는
 * 도메인 {@link CorporateLoan#repay(BigDecimal)} 가 단일 출처로 강제한다.
 */
@Service
public class RepayCorporateLoanService implements RepayCorporateLoanUseCase {

    private final LoadCorporateLoanPort loadCorporateLoanPort;
    private final SaveCorporateLoanPort saveCorporateLoanPort;
    private final AppendLedgerPort appendLedgerPort;
    private final LoanMetricsPort loanMetricsPort;

    public RepayCorporateLoanService(LoadCorporateLoanPort loadCorporateLoanPort,
                                     SaveCorporateLoanPort saveCorporateLoanPort,
                                     AppendLedgerPort appendLedgerPort,
                                     LoanMetricsPort loanMetricsPort) {
        this.loadCorporateLoanPort = loadCorporateLoanPort;
        this.saveCorporateLoanPort = saveCorporateLoanPort;
        this.appendLedgerPort = appendLedgerPort;
        this.loanMetricsPort = loanMetricsPort;
    }

    @Override
    @Transactional
    @Auditable(
            action = AuditAction.CORPORATE_LOAN_REPAID,
            resourceType = "CorporateLoan",
            resourceId = "#p0 == null ? null : #p0.loanId().toString()",
            detail = "{'loanId': #p0.loanId(), 'amount': #p0.amount()}"
    )
    public CorporateLoan repay(RepayCorporateLoanCommand command) {
        CorporateLoan loan = loadCorporateLoanPort.findByIdForUpdate(command.loanId())
                .orElseThrow(() -> new CorporateLoanNotFoundException(
                        "기업대출을 찾을 수 없습니다. loanId=" + command.loanId()));

        BigDecimal deducted = loan.repay(command.amount());
        CorporateLoan saved = saveCorporateLoanPort.save(loan);

        // 복식부기: 상환(현금/대출채권). 차감이 0이면 전표 없음(도메인이 clamp — 실무상 DISBURSED 상환은 항상 >0).
        if (deducted.signum() > 0) {
            appendLedgerPort.append(LoanLedgerEntry.corporateRepayment(saved.getId(), deducted));
        }
        loanMetricsPort.corporateRepaid(deducted);
        return saved;
    }
}
