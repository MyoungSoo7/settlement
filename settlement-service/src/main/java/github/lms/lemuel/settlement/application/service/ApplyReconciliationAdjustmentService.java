package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.in.ApplyReconciliationAdjustmentUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementAdjustmentPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementAdjustment;
import github.lms.lemuel.settlement.domain.exception.SettlementNotFoundException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * PG 대사 승인 → 정산 역정산(clawback) 적용 서비스.
 *
 * <p>{@link AdjustSettlementForRefundService}(환불 역정산)를 미러링하되, 전용 도메인 메서드
 * {@link Settlement#applyReconciliationClawback}를 써서 {@code refundedAmount} running total 을
 * 오염시키지 않는다(실제 환불과의 이중 계상 방지). holdback 우선 차감 → net 축소 → 감사 레코드 순서.
 *
 * <p><b>NO ledger changes.</b> 원장 역분개는 후속 과제다 — 기존 {@code enqueueReverse}는 refundId 키
 * 기반이라 대사에 맞지 않는다. chargeback 경로와 동일하게 이 단계에서는 원장을 건드리지 않는다.
 */
@Service
@Transactional
public class ApplyReconciliationAdjustmentService implements ApplyReconciliationAdjustmentUseCase {

    private static final Logger log = LoggerFactory.getLogger(ApplyReconciliationAdjustmentService.class);

    private static final String METRIC_APPLIED = "pg.reconciliation.adjustments.applied";
    private static final String METRIC_SKIPPED = "pg.reconciliation.adjustments.skipped";

    private final LoadSettlementPort loadSettlementPort;
    private final SaveSettlementPort saveSettlementPort;
    private final SaveSettlementAdjustmentPort saveSettlementAdjustmentPort;
    private final MeterRegistry meterRegistry;

    public ApplyReconciliationAdjustmentService(LoadSettlementPort loadSettlementPort,
                                                SaveSettlementPort saveSettlementPort,
                                                SaveSettlementAdjustmentPort saveSettlementAdjustmentPort,
                                                MeterRegistry meterRegistry) {
        this.loadSettlementPort = loadSettlementPort;
        this.saveSettlementPort = saveSettlementPort;
        this.saveSettlementAdjustmentPort = saveSettlementAdjustmentPort;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void applyClawback(Long paymentId, Long discrepancyId, BigDecimal clawbackAmount) {
        // 멱등 2단(belt-and-suspenders): processed_events 이후에도, 같은 discrepancy 로 조정 row 가
        // 이미 있으면 이중 회수 차단. 재전송·리플레이는 여기서 무해하게 흡수된다.
        if (saveSettlementAdjustmentPort.existsByReconciliationDiscrepancyId(discrepancyId)) {
            log.info("[PgRecon] 이미 적용된 대사 clawback 재전송 스킵. discrepancyId={}, paymentId={}",
                    discrepancyId, paymentId);
            meterRegistry.counter(METRIC_SKIPPED, "reason", "already_applied").increment();
            return;
        }

        // 동시성 제어: 환불 경로와 같은 비관적 락으로 정산 행을 잠근다(동시 조정 lost update 방지).
        Settlement settlement = loadSettlementPort.findByPaymentIdForUpdate(paymentId)
                .orElseThrow(() -> new SettlementNotFoundException(
                        "Settlement not found for paymentId: " + paymentId));

        LocalDate today = LocalDate.now();
        try {
            // holdback 우선 차감(clawback 흡수) → net 축소. consumeHoldbackForRefund 는 사실상
            // "clawback 을 holdback 에서 먼저 흡수" 의미로 재사용한다.
            settlement.consumeHoldbackForRefund(clawbackAmount);
            settlement.applyReconciliationClawback(clawbackAmount); // DONE 이면 여기서 throw
        } catch (IllegalStateException done) {
            // DONE 정산: 도메인이 금액 변경을 거부 → 정산은 저장하지 않는다(holdback 변경도 폐기됨).
            // 갭 추적을 위해 감사 레코드만 남기고 정상 반환한다(chargeback 처럼 실제 회수는 이관).
            // payout 은 되돌리지 않는다(scope 밖).
            saveSettlementAdjustmentPort.save(SettlementAdjustment.ofReconciliation(
                    settlement.getId(), discrepancyId, clawbackAmount, today));
            log.warn("[PgRecon] DONE 정산 — clawback 직접 적용 불가, 감사 레코드만 기록(수기 회수 이관). "
                            + "discrepancyId={}, settlementId={}, clawback={}",
                    discrepancyId, settlement.getId(), clawbackAmount);
            meterRegistry.counter(METRIC_SKIPPED, "reason", "settlement_done_manual_clawback").increment();
            return;
        }

        Settlement adjusted = saveSettlementPort.save(settlement);

        saveSettlementAdjustmentPort.save(SettlementAdjustment.ofReconciliation(
                adjusted.getId(), discrepancyId, clawbackAmount, today));

        log.info("[PgRecon] 대사 clawback 적용 완료. discrepancyId={}, settlementId={}, clawback={}, netAmount={}, status={}",
                discrepancyId, adjusted.getId(), clawbackAmount, adjusted.getNetAmount(), adjusted.getStatus());
        meterRegistry.counter(METRIC_APPLIED).increment();
    }
}
