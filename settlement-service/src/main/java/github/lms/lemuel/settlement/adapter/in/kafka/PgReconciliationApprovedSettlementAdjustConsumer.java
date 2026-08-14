package github.lms.lemuel.settlement.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ConsumedEventQuarantine;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.settlement.application.port.in.ApplyReconciliationAdjustmentUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import github.lms.lemuel.settlement.domain.ClawbackPolicy;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * PgReconciliationDiscrepancyApproved 이벤트 → 정산 역정산(clawback) 트리거.
 *
 * <p>운영자가 승인한 PG 대사 차이 중 <b>회수(clawback) 방향</b>으로 확정된 건에 한해
 * {@link ApplyReconciliationAdjustmentUseCase} 를 호출해 셀러 과다 정산분을 정산금(net)에서 회수한다.
 * clawback 은 net 축소로만 payout 에 반영된다(payout 은 {@code netAmount − holdback} 으로 계산하며
 * SettlementAdjustment row 자체는 감사·리포팅용).
 *
 * <p><b>회수 방향 판정(타입별)</b>:
 * <ul>
 *   <li>{@code AMOUNT_MISMATCH} 이고 difference &lt; 0 (pgAmount &lt; internalAmount → 셀러 과다 정산):
 *       clawback = |difference|</li>
 *   <li>{@code MISSING_PG} (내부에만 존재, PG 미송금): clawback = internalAmount</li>
 *   <li>그 외 — {@code AMOUNT_MISMATCH} diff&gt;0(과소 정산, 더 줘야 함), {@code MISSING_INTERNAL}
 *       (paymentId 없음 → 정산 없음), {@code DUPLICATE}(PG 측 이중청구), {@code ROUNDING_DIFF}: 조정 없음.
 *       조용히 버리지 않고 {@code pg.reconciliation.adjustments.skipped{reason}} 메트릭 증가 + WARN 후
 *       정상 반환해 골격이 processed 마커를 남기게 한다(무한 재시도 방지).</li>
 * </ul>
 *
 * <p>멱등 골격은 {@link IdempotentEventConsumer}(processed_events) 가 소유하고,
 * UseCase 가 discrepancyId 기준 belt-and-suspenders 2단 방어를 추가한다.
 *
 * <p>실패 정책(fail-loud): 회수 대상 타입인데 정산이 없으면(paymentId 있음+정산 미존재) UseCase 가
 * 예외를 전파 → 컨테이너 재시도 후 DLT. "승인했는데 정산이 없다"를 드러낸다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class PgReconciliationApprovedSettlementAdjustConsumer extends IdempotentEventConsumer {

    private static final String CONSUMER_GROUP = "lemuel-settlement-recon-adjust";
    private static final String METRIC_SKIPPED = "pg.reconciliation.adjustments.skipped";

    private final ApplyReconciliationAdjustmentUseCase applyReconciliationAdjustmentUseCase;
    private final MeterRegistry meterRegistry;

    public PgReconciliationApprovedSettlementAdjustConsumer(
            ApplyReconciliationAdjustmentUseCase applyReconciliationAdjustmentUseCase,
            ProcessedEventRepository processedEventRepository,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            ConsumedEventQuarantine quarantine) {
        super(processedEventRepository, objectMapper, quarantine);
        this.applyReconciliationAdjustmentUseCase = applyReconciliationAdjustmentUseCase;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = "${app.kafka.topic.pg-recon-approved:lemuel.pgreconciliation.discrepancy_approved}",
            groupId = CONSUMER_GROUP,
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onDiscrepancyApproved(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return CONSUMER_GROUP;
    }

    @Override
    protected String eventType() {
        return "PgReconciliationDiscrepancyApproved";
    }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        Long discrepancyId = requiredLong(node, "discrepancyId", eventId);
        String type = node.hasNonNull("type") ? node.get("type").asText() : null;
        Long paymentId = node.hasNonNull("paymentId") ? node.get("paymentId").asLong() : null;
        BigDecimal internalAmount = readAmount(node, "internalAmount");
        BigDecimal difference = readAmount(node, "difference"); // pgAmount - internalAmount (signed)

        BigDecimal clawback = ClawbackPolicy.computeFor(type, internalAmount, difference);
        if (clawback == null || clawback.signum() <= 0) {
            skip(skipReason(type, difference), discrepancyId, type, eventId);
            return;
        }
        if (paymentId == null) {
            // 회수 대상 타입은 paymentId 를 가져야 정상 — 방어적으로 스킵(정상 반환).
            skip("no_payment_id", discrepancyId, type, eventId);
            return;
        }

        // 회수 대상 타입인데 정산이 없으면 UseCase 가 예외 전파 → DLT (fail-loud).
        applyReconciliationAdjustmentUseCase.applyClawback(paymentId, discrepancyId, clawback);
    }

    // 회수액 산정 규칙은 도메인(ClawbackPolicy)이 단일 출처다 — 승인 미리보기와 실제 적용이
    // 같은 규칙을 봐야 "미리보기와 다르게 회수됐다"가 생기지 않는다.

    private void skip(String reason, Long discrepancyId, String type, UUID eventId) {
        meterRegistry.counter(METRIC_SKIPPED, "reason", reason).increment();
        log.warn("[PgRecon] clawback 대상 아님 — 조정 스킵. reason={}, discrepancyId={}, type={}, eventId={}",
                reason, discrepancyId, type, eventId);
    }

    private static String skipReason(String type, BigDecimal difference) {
        if (type == null) {
            return "unknown_type";
        }
        switch (type) {
            case "AMOUNT_MISMATCH":
                return "amount_mismatch_under_settlement"; // diff >= 0
            case "MISSING_INTERNAL":
                return "missing_internal_no_settlement";
            case "DUPLICATE":
                return "duplicate_pg_side";
            case "ROUNDING_DIFF":
                return "rounding_diff";
            case "FEE_MISMATCH":
                // 실입금 불일치는 PG 수수료 구조 오인/파일 오류에서 난다 — 셀러가 과다 정산을 받은 게
                // 아니므로 셀러에게서 회수하지 않는다. unknown_type 으로 찍히면 미처리 버그로 오인된다.
                return "fee_mismatch_pg_side";
            case "MISSING_PG":
                return "missing_pg_no_internal_amount";
            default:
                return "unknown_type";
        }
    }

    private static BigDecimal readAmount(JsonNode node, String field) {
        // 금액은 문자열/숫자 어느 쪽이든 BigDecimal 로 안전 파싱(money-safety: double 경유 금지).
        return node.hasNonNull(field) ? new BigDecimal(node.get(field).asText()) : null;
    }
}
