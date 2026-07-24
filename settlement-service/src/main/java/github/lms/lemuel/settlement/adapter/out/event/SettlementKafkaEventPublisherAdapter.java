package github.lms.lemuel.settlement.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.settlement.application.port.out.PublishSettlementDomainEventPort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 정산 도메인 이벤트를 Transactional Outbox 에 기록한다. 정산 생성/확정 트랜잭션과 같은 트랜잭션에서
 * 저장되어 원자성이 보장되고, shared-common 의 OutboxPublisherScheduler 가 Kafka 로 발행한다.
 *
 * <p>aggregateType="Settlement" → 토픽 lemuel.settlement.created / lemuel.settlement.confirmed (loan 구독).
 */
@Component
public class SettlementKafkaEventPublisherAdapter implements PublishSettlementDomainEventPort {

    private static final String AGGREGATE_TYPE = "Settlement";

    private final SaveOutboxEventPort saveOutboxEventPort;
    private final ObjectMapper objectMapper;

    public SettlementKafkaEventPublisherAdapter(SaveOutboxEventPort saveOutboxEventPort,
                                                ObjectMapper objectMapper) {
        this.saveOutboxEventPort = saveOutboxEventPort;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishSettlementCreated(long settlementId, long sellerId, BigDecimal amount,
                                         LocalDate dueDate, BigDecimal holdbackAmount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("settlementId", settlementId);
        payload.put("sellerId", sellerId);
        payload.put("amount", amount);
        payload.put("dueDate", dueDate != null ? dueDate.toString() : null); // ISO-8601 문자열 (loan 이 LocalDate.parse)
        payload.put("holdbackAmount", holdbackAmount); // ADR 0026 Option ① — account 유보분 분할 전기용(>=0)
        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE, String.valueOf(settlementId), "SettlementCreated", toJson(payload)));
    }

    @Override
    public void publishSettlementConfirmed(long settlementId, long sellerId, BigDecimal amount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("settlementId", settlementId);
        payload.put("sellerId", sellerId);
        payload.put("amount", amount);
        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE, String.valueOf(settlementId), "SettlementConfirmed", toJson(payload)));
    }

    @Override
    public void publishHoldbackReleased(long settlementId, long sellerId, BigDecimal amount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("settlementId", settlementId);
        payload.put("sellerId", sellerId);
        payload.put("amount", amount);
        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE, String.valueOf(settlementId), "SettlementHoldbackReleased", toJson(payload)));
    }

    @Override
    public void publishHoldbackConsumed(long sourceAdjustmentId, Long settlementId, long sellerId, BigDecimal amount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceAdjustmentId", sourceAdjustmentId);
        if (settlementId != null) {
            payload.put("settlementId", settlementId); // 수동 경로에서 미상이면 생략(계약상 optional integer, null 불가)
        }
        payload.put("sellerId", sellerId);
        payload.put("amount", amount);
        // aggregateId 는 소진의 인과 키(조정 id) — 같은 조정 재전송이 같은 키로 파티션 순서 보장.
        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE, String.valueOf(sourceAdjustmentId), "SettlementHoldbackConsumed", toJson(payload)));
    }

    @Override
    public void publishSettlementAdjusted(long adjustmentId, Long settlementId, long sellerId,
                                          BigDecimal amount, String targetLeg) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("adjustmentId", adjustmentId);
        if (settlementId != null) {
            payload.put("settlementId", settlementId); // optional integer — 미상 시 생략(null 불가)
        }
        payload.put("sellerId", sellerId);
        payload.put("amount", amount);
        payload.put("targetLeg", targetLeg); // "SELLER_PAYABLE" | "HOLDBACK_PAYABLE"
        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE, String.valueOf(adjustmentId), "SettlementAdjusted", toJson(payload)));
    }

    @Override
    public void publishSettlementCanceled(long settlementId, long sellerId,
                                          BigDecimal immediateAmount, BigDecimal holdbackAmount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("settlementId", settlementId);
        payload.put("sellerId", sellerId);
        payload.put("immediateAmount", immediateAmount);
        payload.put("holdbackAmount", holdbackAmount);
        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE, String.valueOf(settlementId), "SettlementCanceled", toJson(payload)));
    }

    @Override
    public void publishWithholdingAccrued(long settlementId, long sellerId, BigDecimal withholdingAmount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("settlementId", settlementId);
        payload.put("sellerId", sellerId);
        payload.put("withholdingAmount", withholdingAmount);
        // eventType="SettlementWithholdingAccrued" → prefix("Settlement") 제거 후 camel→snake
        // → "lemuel.settlement.withholding_accrued" (KafkaOutboxPublisher.resolveTopic).
        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE, String.valueOf(settlementId), "SettlementWithholdingAccrued", toJson(payload)));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("정산 이벤트 직렬화 실패", e);
        }
    }
}
