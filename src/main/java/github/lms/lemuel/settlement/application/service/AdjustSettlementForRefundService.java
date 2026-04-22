package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.in.AdjustSettlementForRefundUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementAdjustmentPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementAdjustment;
import github.lms.lemuel.settlement.domain.exception.SettlementNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 환불 발생 시 정산 조정 서비스
 */
@Service
@Transactional
public class AdjustSettlementForRefundService implements AdjustSettlementForRefundUseCase {

    private static final Logger log = LoggerFactory.getLogger(AdjustSettlementForRefundService.class);

    private final LoadSettlementPort loadSettlementPort;
    private final SaveSettlementPort saveSettlementPort;
    private final SaveSettlementAdjustmentPort saveSettlementAdjustmentPort;

    public AdjustSettlementForRefundService(LoadSettlementPort loadSettlementPort,
                                            SaveSettlementPort saveSettlementPort,
                                            SaveSettlementAdjustmentPort saveSettlementAdjustmentPort) {
        this.loadSettlementPort = loadSettlementPort;
        this.saveSettlementPort = saveSettlementPort;
        this.saveSettlementAdjustmentPort = saveSettlementAdjustmentPort;
    }

    @Override
    public Settlement adjustSettlementForRefund(Long paymentId, BigDecimal refundAmount) {
        log.info("Adjusting settlement for refund. paymentId={}, refundAmount={}", paymentId, refundAmount);

        // 해당 결제의 정산 조회
        Settlement settlement = loadSettlementPort.findByPaymentId(paymentId)
                .orElseThrow(() -> new SettlementNotFoundException("Settlement not found for paymentId: " + paymentId));

        // 환불 반영 (도메인 로직) — 정산의 netAmount 재계산 (running total)
        settlement.adjustForRefund(refundAmount);
        Settlement adjustedSettlement = saveSettlementPort.save(settlement);

        // 감사 추적: 별도 음수 금액 레코드로 역정산 이력 보존
        SettlementAdjustment adjustment = SettlementAdjustment.ofRefund(
                adjustedSettlement.getId(),
                refundAmount,
                LocalDate.now()
        );
        saveSettlementAdjustmentPort.save(adjustment);

        log.info("Settlement adjusted for refund. settlementId={}, status={}, netAmount={}, adjustmentAmount={}",
                adjustedSettlement.getId(), adjustedSettlement.getStatus(),
                adjustedSettlement.getNetAmount(), adjustment.getAmount());

        return adjustedSettlement;
    }
}
