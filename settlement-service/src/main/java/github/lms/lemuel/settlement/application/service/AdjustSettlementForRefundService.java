package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.ledger.application.port.in.EnqueueLedgerTaskPort;
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
import java.time.Clock;
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
    private final EnqueueLedgerTaskPort enqueueLedgerTaskPort;
    /** KST 기준 시각 소스 — 조정/역분개 기준일이 JVM 타임존에 흔들리지 않게 한다. */
    private final Clock clock;

    public AdjustSettlementForRefundService(LoadSettlementPort loadSettlementPort,
                                            SaveSettlementPort saveSettlementPort,
                                            SaveSettlementAdjustmentPort saveSettlementAdjustmentPort,
                                            EnqueueLedgerTaskPort enqueueLedgerTaskPort,
                                            Clock clock) {
        this.loadSettlementPort = loadSettlementPort;
        this.saveSettlementPort = saveSettlementPort;
        this.saveSettlementAdjustmentPort = saveSettlementAdjustmentPort;
        this.enqueueLedgerTaskPort = enqueueLedgerTaskPort;
        this.clock = clock;
    }

    @Override
    public Settlement adjustSettlementForRefund(Long paymentId, BigDecimal refundAmount, Long refundId) {
        log.info("Adjusting settlement for refund. paymentId={}, refundAmount={}, refundId={}",
                paymentId, refundAmount, refundId);

        // 해당 결제의 정산을 비관적 락(SELECT ... FOR UPDATE)으로 조회.
        // 동시 환불 2건이 같은 refundedAmount/holdback 을 읽고 각자 차감해 덮어쓰는 lost update 방지.
        Settlement settlement = loadSettlementPort.findByPaymentIdForUpdate(paymentId)
                .orElseThrow(() -> new SettlementNotFoundException("Settlement not found for paymentId: " + paymentId));

        // ★ Holdback 우선 차감 정책: 보류금이 있으면 거기서 먼저 빼서 셀러 추가 부담 없게 한다.
        // 신뢰도 낮은 셀러의 환불 다발 위험을 정산 사이클 안에서 흡수하는 안전장치.
        BigDecimal consumedFromHoldback = settlement.consumeHoldbackForRefund(refundAmount);
        if (consumedFromHoldback.signum() > 0) {
            log.info("Holdback 에서 우선 차감: settlementId={}, consumed={}, holdbackRemaining={}",
                    settlement.getId(), consumedFromHoldback, settlement.getHoldbackAmount());
        }

        // 환불 반영 (도메인 로직) — 정산의 netAmount 재계산 (running total)
        settlement.adjustForRefund(refundAmount);
        Settlement adjustedSettlement = saveSettlementPort.save(settlement);

        // 감사 추적: 별도 음수 금액 레코드로 역정산 이력 보존 — refundId 로 환불과 1:1 매핑
        LocalDate today = LocalDate.now(clock);
        SettlementAdjustment adjustment = SettlementAdjustment.ofRefund(
                adjustedSettlement.getId(),
                refundId,
                refundAmount,
                today
        );
        saveSettlementAdjustmentPort.save(adjustment);

        log.info("Settlement adjusted for refund. settlementId={}, status={}, netAmount={}, adjustmentAmount={}",
                adjustedSettlement.getId(), adjustedSettlement.getStatus(),
                adjustedSettlement.getNetAmount(), adjustment.getAmount());

        // refundId 가 있을 때만 ledger 역분개 트리거 — 레거시 2-arg 호출 경로 보호.
        // 같은 트랜잭션 아웃박스에 적재 → 커밋 후 로컬 폴러가 멱등 역분개 처리 (크래시 내성).
        if (refundId != null) {
            enqueueLedgerTaskPort.enqueueReverse(
                    adjustedSettlement.getId(), refundId, refundAmount, today);
        }

        return adjustedSettlement;
    }
}
