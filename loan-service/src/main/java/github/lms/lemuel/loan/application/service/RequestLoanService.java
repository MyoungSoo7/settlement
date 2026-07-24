package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.common.audit.application.Auditable;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.loan.application.port.in.RequestLoanUseCase;
import github.lms.lemuel.loan.application.port.out.LoadSellerReputationPort;
import github.lms.lemuel.loan.application.port.out.LoadSettlementViewPort;
import github.lms.lemuel.loan.application.port.out.LoanMetricsPort;
import github.lms.lemuel.loan.application.port.out.SaveLoanPort;
import github.lms.lemuel.loan.domain.LoanAdvance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 대출 신청: 로컬 정산뷰의 미지급 정산예정금(담보)으로 한도를 검증하고 수수료를 산정해
 * REQUESTED 상태로 등록한다. 실제 자금 실행은 {@link DisburseLoanService} 에서 재검증 후 이뤄진다.
 *
 * <p>한도 검증에는 셀러(법인)의 뉴스 평판 등급이 반영된다(ADR 0023 Phase 3 후속) —
 * 등급이 나쁘면 CreditPolicy 가 한도를 haircut 한다. 평판 미상이면 무변동(fail-open).
 *
 * <p>선지급일수(financingDays)는 수수료 산정 근거이자 실행 시 만기(dueAt) 계산의 근거라 애그리거트에
 * 보존한다(자동 연체/상각 배치가 만기를 스캔).
 */
@Service
public class RequestLoanService implements RequestLoanUseCase {

    private final LoadSettlementViewPort loadSettlementViewPort;
    private final LoadSellerReputationPort loadSellerReputationPort;
    private final SaveLoanPort saveLoanPort;
    private final CreditPolicy creditPolicy;
    private final LoanMetricsPort loanMetricsPort;

    public RequestLoanService(LoadSettlementViewPort loadSettlementViewPort,
                              LoadSellerReputationPort loadSellerReputationPort,
                              SaveLoanPort saveLoanPort,
                              CreditPolicy creditPolicy,
                              LoanMetricsPort loanMetricsPort) {
        this.loadSettlementViewPort = loadSettlementViewPort;
        this.loadSellerReputationPort = loadSellerReputationPort;
        this.saveLoanPort = saveLoanPort;
        this.creditPolicy = creditPolicy;
        this.loanMetricsPort = loanMetricsPort;
    }

    @Override
    @Transactional
    @Auditable(
            action = AuditAction.LOAN_ADVANCE_REQUESTED,
            resourceType = "LoanAdvance",
            resourceId = "#result == null ? null : #result.getId().toString()",
            detail = "{'sellerId': #p0.sellerId(), 'principal': #p0.principal(), 'financingDays': #p0.financingDays()}"
    )
    public LoanAdvance request(RequestLoanCommand command) {
        BigDecimal unpaidSettlement = loadSettlementViewPort.sumUnpaidBySeller(command.sellerId());
        String reputationGrade = loadSellerReputationPort.findGrade(command.sellerId()).orElse(null);
        creditPolicy.validateWithinLimit(command.principal(), unpaidSettlement, reputationGrade);

        BigDecimal fee = creditPolicy.fee(command.principal(), command.financingDays());
        // 선지급일수를 애그리거트에 보존 — 실행 시 만기(dueAt = disbursedAt + financingDays) 계산의 근거.
        LoanAdvance loan = LoanAdvance.request(command.sellerId(), command.principal(), fee,
                command.financingDays());
        LoanAdvance saved = saveLoanPort.save(loan);
        loanMetricsPort.advanceRequested();
        return saved;
    }
}
