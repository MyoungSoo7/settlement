package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.common.audit.application.Auditable;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.loan.application.port.in.DisburseLoanUseCase;
import github.lms.lemuel.loan.application.port.out.AppendLedgerPort;
import github.lms.lemuel.loan.application.port.out.LoadLoanPort;
import github.lms.lemuel.loan.application.port.out.LoadSellerReputationPort;
import github.lms.lemuel.loan.application.port.out.LoadSettlementViewPort;
import github.lms.lemuel.loan.application.port.out.LoanMetricsPort;
import github.lms.lemuel.loan.application.port.out.PublishLoanEventPort;
import github.lms.lemuel.loan.application.port.out.SaveLoanPort;
import github.lms.lemuel.loan.domain.LoanAdvance;
import github.lms.lemuel.loan.domain.LoanLedgerEntry;
import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 대출 실행(선지급).
 *
 * <p>신청 시점과 실행 시점 사이에 담보(정산예정금)가 변할 수 있으므로, 실행 직전에
 * 비관적 락으로 미지급 합계를 재조회해 한도를 재검증한다(동시 선지급 경합 직렬화).
 * 통과 시 DISBURSED 로 전이하고 LoanDisbursementRequested 를 Outbox 에 기록 →
 * settlement 가 payout 으로 셀러에게 실제 송금한다.
 */
@Service
public class DisburseLoanService implements DisburseLoanUseCase {

    private final LoadLoanPort loadLoanPort;
    private final SaveLoanPort saveLoanPort;
    private final LoadSettlementViewPort loadSettlementViewPort;
    private final LoadSellerReputationPort loadSellerReputationPort;
    private final CreditPolicy creditPolicy;
    private final PublishLoanEventPort publishLoanEventPort;
    private final AppendLedgerPort appendLedgerPort;
    private final LoanMetricsPort loanMetricsPort;

    public DisburseLoanService(LoadLoanPort loadLoanPort,
                               SaveLoanPort saveLoanPort,
                               LoadSettlementViewPort loadSettlementViewPort,
                               LoadSellerReputationPort loadSellerReputationPort,
                               CreditPolicy creditPolicy,
                               PublishLoanEventPort publishLoanEventPort,
                               AppendLedgerPort appendLedgerPort,
                               LoanMetricsPort loanMetricsPort) {
        this.loadLoanPort = loadLoanPort;
        this.saveLoanPort = saveLoanPort;
        this.loadSettlementViewPort = loadSettlementViewPort;
        this.loadSellerReputationPort = loadSellerReputationPort;
        this.creditPolicy = creditPolicy;
        this.publishLoanEventPort = publishLoanEventPort;
        this.appendLedgerPort = appendLedgerPort;
        this.loanMetricsPort = loanMetricsPort;
    }

    @Override
    @Transactional
    @Auditable(
            action = AuditAction.LOAN_ADVANCE_DISBURSED,
            resourceType = "LoanAdvance",
            resourceId = "#p0 == null ? null : #p0.toString()",
            detail = "{'loanId': #p0, 'status': #result == null ? null : #result.getStatus().name()}"
    )
    public LoanAdvance disburse(Long loanId) {
        LoanAdvance loan = loadLoanPort.load(loanId);
        loan.approve();

        // 실행 직전 담보 + 평판 재검증 (비관적 락으로 동시 실행 직렬화 / 신청 이후 평판 악화 반영)
        BigDecimal freshUnpaid = loadSettlementViewPort.sumUnpaidBySellerForUpdate(loan.getSellerId());
        String grade = loadSellerReputationPort.findGrade(loan.getSellerId()).orElse(null);
        try {
            creditPolicy.validateWithinLimit(loan.getPrincipal(), freshUnpaid, grade);
        } catch (LoanInvariantViolationException e) {
            // 실행 시점 담보 부족(한도 초과) → 대출 거절 상태로 전이·기록 후, 거절 사유(요청액/한도)를
            // 그대로 담은 도메인 예외를 전파한다(generic 래핑으로 사유를 유실하지 않는다). 웹 계약 400 불변.
            loan.reject();
            saveLoanPort.save(loan);
            loanMetricsPort.advanceRejected();
            throw e;
        }

        loan.disburse();
        LoanAdvance saved = saveLoanPort.save(loan);

        // 복식부기: 선지급(대출채권/현금) + 수수료 인식(미수수익/수수료수익)
        appendLedgerPort.append(LoanLedgerEntry.disbursement(saved.getId(), saved.getPrincipal()));
        if (saved.getFee().signum() > 0) {
            appendLedgerPort.append(LoanLedgerEntry.feeAccrual(saved.getId(), saved.getFee()));
        }

        publishLoanEventPort.publishDisbursementRequested(saved);
        loanMetricsPort.advanceDisbursed();
        return saved;
    }
}
