package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.in.AdjustSettlementForRefundUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.exception.SettlementNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 환불 발생 시 정산 조정 서비스
 */
@Service
@Transactional
public class AdjustSettlementForRefundService implements AdjustSettlementForRefundUseCase {

    private static final Logger log = LoggerFactory.getLogger(AdjustSettlementForRefundService.class);

    private final LoadSettlementPort loadSettlementPort;
    private final SaveSettlementPort saveSettlementPort;

    public AdjustSettlementForRefundService(LoadSettlementPort loadSettlementPort,
                                            SaveSettlementPort saveSettlementPort) {
        this.loadSettlementPort = loadSettlementPort;
        this.saveSettlementPort = saveSettlementPort;
    }

    @Override
    public Settlement adjustSettlementForRefund(Long paymentId, BigDecimal refundAmount) {
        log.info("Adjusting settlement for refund. paymentId={}, refundAmount={}", paymentId, refundAmount);

        // 해당 결제의 정산 조회
        Settlement settlement = loadSettlementPort.findByPaymentId(paymentId)
                .orElseThrow(() -> new SettlementNotFoundException("Settlement not found for paymentId: " + paymentId));

        // 환불 반영 (도메인 로직)
        settlement.adjustForRefund(refundAmount);

        // 저장
        Settlement adjustedSettlement = saveSettlementPort.save(settlement);
        log.info("Settlement adjusted for refund. settlementId={}, status={}, netAmount={}",
                adjustedSettlement.getId(), adjustedSettlement.getStatus(), adjustedSettlement.getNetAmount());

        return adjustedSettlement;
    }

    @Override
    public void adjustSettlementForRefund(Long refundId, Long paymentId, BigDecimal refundAmount) {
        // Task 3.3에서 재작성 (Settlement immutable + SettlementAdjustment INSERT + Ledger 분개)
        adjustSettlementForRefund(paymentId, refundAmount);
    }
}
