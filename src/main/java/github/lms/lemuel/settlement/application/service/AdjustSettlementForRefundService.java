package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.ledger.application.port.in.RecordJournalEntryUseCase;
import github.lms.lemuel.ledger.domain.Money;
import github.lms.lemuel.settlement.application.port.in.AdjustSettlementForRefundUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementAdjustmentPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementAdjustment;
import github.lms.lemuel.settlement.domain.exception.SettlementNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * 환불 발생 시 정산 조정 서비스.
 *
 * <p>회계 immutability 원칙:
 * <ul>
 *   <li>원 Settlement는 변경하지 않는다 (audit immutability).</li>
 *   <li>환불별 SettlementAdjustment 1건 INSERT.</li>
 *   <li>Ledger {@code recordRefundProcessed} 호출 ({@code refundId}로 idempotency 보장).</li>
 *   <li>비례 수수료 환급 = settlement.commission * (refundAmount / settlement.paymentAmount), HALF_UP scale 2.</li>
 * </ul>
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class AdjustSettlementForRefundService implements AdjustSettlementForRefundUseCase {

    private final LoadSettlementPort loadSettlementPort;
    private final SaveSettlementAdjustmentPort saveSettlementAdjustmentPort;
    private final RecordJournalEntryUseCase recordJournalEntryUseCase;

    @Override
    public void adjustSettlementForRefund(Long refundId, Long paymentId, BigDecimal refundAmount) {
        Settlement settlement = loadSettlementPort.findByPaymentId(paymentId)
                .orElseThrow(() -> new SettlementNotFoundException(
                        "Settlement not found for paymentId=" + paymentId));

        // 1. Adjustment INSERT (원 Settlement는 mutate하지 않음)
        SettlementAdjustment adjustment = SettlementAdjustment.forRefund(
                settlement.getId(), refundId, refundAmount, LocalDate.now());
        SettlementAdjustment savedAdjustment = saveSettlementAdjustmentPort.save(adjustment);

        // 2. 비례 수수료 환급 계산: commission * (refundAmount / paymentAmount), HALF_UP scale 2
        BigDecimal commissionReversal = settlement.getCommission()
                .multiply(refundAmount)
                .divide(settlement.getPaymentAmount(), 2, RoundingMode.HALF_UP);

        // 3. Ledger 분개 기록 (refundId로 멱등 보장)
        recordJournalEntryUseCase.recordRefundProcessed(
                refundId,
                settlement.getSellerId(),
                Money.krw(refundAmount),
                Money.krw(commissionReversal)
        );

        log.info("Refund adjustment recorded. settlementId={}, adjustmentId={}, refundId={}, refund={}, commissionReversal={}",
                settlement.getId(), savedAdjustment.getId(), refundId, refundAmount, commissionReversal);
    }
}
