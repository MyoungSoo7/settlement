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
    public void publishSettlementCreated(long settlementId, long sellerId, BigDecimal amount, LocalDate dueDate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("settlementId", settlementId);
        payload.put("sellerId", sellerId);
        payload.put("amount", amount);
        payload.put("dueDate", dueDate != null ? dueDate.toString() : null); // ISO-8601 문자열 (loan 이 LocalDate.parse)
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

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("정산 이벤트 직렬화 실패", e);
        }
    }
}
