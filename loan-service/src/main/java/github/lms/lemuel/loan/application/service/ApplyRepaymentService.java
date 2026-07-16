package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.common.audit.application.Auditable;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.loan.application.port.in.ApplyRepaymentUseCase;
import github.lms.lemuel.loan.application.port.out.AppendLedgerPort;
import github.lms.lemuel.loan.application.port.out.LoadLoanPort;
import github.lms.lemuel.loan.application.port.out.LoanMetricsPort;
import github.lms.lemuel.loan.application.port.out.PublishLoanEventPort;
import github.lms.lemuel.loan.application.port.out.RecordRepaymentPort;
import github.lms.lemuel.loan.application.port.out.SaveLoanPort;
import github.lms.lemuel.loan.application.port.out.SaveSettlementViewPort;
import github.lms.lemuel.loan.domain.LoanAdvance;
import github.lms.lemuel.loan.domain.LoanLedgerEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 상환 saga 의 loan 측 단계.
 *
 * <p>정산 확정 시 셀러의 미상환 대출을 FIFO(오래된 순)로 차감하고, 차감 총액을
 * LoanRepaymentApplied 로 발행한다 → settlement 가 순지급액(amount - deducted)으로 payout.
 *
 * <p>멱등: settlementId 기준 — 이미 처리됐으면 재차감하지 않는다(컨슈머 processed_events 와 이중 방어,
 * loan_repayments.settlement_id UNIQUE 가 스키마 최종 방어).
 */
@Service
public class ApplyRepaymentService implements ApplyRepaymentUseCase {

    private static final Logger log = LoggerFactory.getLogger(ApplyRepaymentService.class);

    private final LoadLoanPort loadLoanPort;
    private final SaveLoanPort saveLoanPort;
    private final RecordRepaymentPort recordRepaymentPort;
    private final SaveSettlementViewPort saveSettlementViewPort;
    private final PublishLoanEventPort publishLoanEventPort;
    private final AppendLedgerPort appendLedgerPort;
    private final LoanMetricsPort loanMetricsPort;

    public ApplyRepaymentService(LoadLoanPort loadLoanPort,
                                 SaveLoanPort saveLoanPort,
                                 RecordRepaymentPort recordRepaymentPort,
                                 SaveSettlementViewPort saveSettlementViewPort,
                                 PublishLoanEventPort publishLoanEventPort,
                                 AppendLedgerPort appendLedgerPort,
                                 LoanMetricsPort loanMetricsPort) {
        this.loadLoanPort = loadLoanPort;
        this.saveLoanPort = saveLoanPort;
        this.recordRepaymentPort = recordRepaymentPort;
        this.saveSettlementViewPort = saveSettlementViewPort;
        this.publishLoanEventPort = publishLoanEventPort;
        this.appendLedgerPort = appendLedgerPort;
        this.loanMetricsPort = loanMetricsPort;
    }

    @Override
    @Transactional
    @Auditable(
            action = AuditAction.LOAN_REPAYMENT_APPLIED,
            resourceType = "LoanRepayment",
            resourceId = "#p0 == null ? null : #p0.settlementId().toString()",
            detail = "{'settlementId': #p0.settlementId(), 'sellerId': #p0.sellerId(), 'amount': #p0.amount()}"
    )
    public void apply(ApplyRepaymentCommand command) {
        long settlementId = command.settlementId();
        if (recordRepaymentPort.existsForSettlement(settlementId)) {
            log.info("이미 상환 처리된 정산건 스킵. settlementId={}", settlementId);
            return;
        }

        saveSettlementViewPort.markConfirmed(settlementId);

        // 미상환 대출을 FIFO(오래된 순)로 락 조회 후 순차 차감
        List<LoanAdvance> loans = loadLoanPort.findDisbursedBySellerForUpdate(command.sellerId());
        BigDecimal remaining = command.amount();
        BigDecimal totalDeducted = BigDecimal.ZERO;

        for (LoanAdvance loan : loans) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal deducted = loan.applyRepayment(remaining);
            if (deducted.signum() > 0) {
                saveLoanPort.save(loan);
                totalDeducted = totalDeducted.add(deducted);
                remaining = remaining.subtract(deducted);
            }
        }

        // 복식부기: 상환(현금/대출채권). 차감이 0이면 전표 없음.
        if (totalDeducted.signum() > 0) {
            appendLedgerPort.append(LoanLedgerEntry.repayment(settlementId, totalDeducted));
        }

        // 차감이 0(대출 없음)이어도 기록·발행한다 → settlement 가 전액 지급하도록 통지(멱등 보장)
        recordRepaymentPort.record(settlementId, command.sellerId(), totalDeducted);
        publishLoanEventPort.publishRepaymentApplied(settlementId, command.sellerId(), totalDeducted);
        loanMetricsPort.repaymentApplied(totalDeducted);

        log.info("상환 차감 완료. settlementId={}, sellerId={}, deducted={}",
                settlementId, command.sellerId(), totalDeducted);
    }
}
