package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.in.ApplyLoanDeductionUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.RecordLoanDeductionPort;
import github.lms.lemuel.settlement.domain.Settlement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 상환 saga 의 settlement 측 단계: loan 차감을 정산건별로 보존하고 순지급액을 산출한다.
 *
 * <p>차감 0(대출 없는 셀러)도 기록되어, payout 트리거가 정상적으로 전액 지급할 수 있다.
 */
@Service
public class ApplyLoanDeductionService implements ApplyLoanDeductionUseCase {

    private static final Logger log = LoggerFactory.getLogger(ApplyLoanDeductionService.class);

    private final RecordLoanDeductionPort recordLoanDeductionPort;
    private final LoadSettlementPort loadSettlementPort;

    public ApplyLoanDeductionService(RecordLoanDeductionPort recordLoanDeductionPort,
                                     LoadSettlementPort loadSettlementPort) {
        this.recordLoanDeductionPort = recordLoanDeductionPort;
        this.loadSettlementPort = loadSettlementPort;
    }

    @Override
    @Transactional
    public void apply(long settlementId, long sellerId, BigDecimal deducted) {
        recordLoanDeductionPort.record(settlementId, sellerId, deducted);
        log.info("[LoanDeduction] 반영: settlementId={}, sellerId={}, deducted={}",
                settlementId, sellerId, deducted);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal netPayoutFor(long settlementId) {
        Settlement settlement = loadSettlementPort.findById(settlementId)
                .orElseThrow(() -> new IllegalArgumentException("정산을 찾을 수 없습니다. settlementId=" + settlementId));
        BigDecimal deducted = recordLoanDeductionPort.findDeduction(settlementId).orElse(BigDecimal.ZERO);
        return settlement.getNetAmount().subtract(deducted).max(BigDecimal.ZERO);
    }
}
