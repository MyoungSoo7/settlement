package github.lms.lemuel.recovery.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.recovery.application.port.out.PublishSellerRecoveryEventPort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 지급후 회수 채권(SellerRecovery) 이벤트를 Transactional Outbox 에 기록한다. 채권 발생·상계 트랜잭션과
 * 같은 트랜잭션에서 저장되어 원자성이 보장되고, shared-common 의 OutboxPublisherScheduler 가 Kafka 로 발행한다.
 *
 * <p>aggregateType 은 리터럴 "seller_recovery" — 라우터가 aggregate 세그먼트를 snake 변환하지 않고
 * 소문자화만 하므로, lemuel.seller_recovery.opened / .offset 을 얻으려면 언더스코어를 그대로 실어야 한다
 * (ADR 0026 Option ①). settlement 계열 규약대로 amount 는 JSON number 로 직렬화된다.
 */
@Component
public class SellerRecoveryKafkaEventPublisherAdapter implements PublishSellerRecoveryEventPort {

    private static final String AGGREGATE_TYPE = "seller_recovery";

    private final SaveOutboxEventPort saveOutboxEventPort;
    private final ObjectMapper objectMapper;

    public SellerRecoveryKafkaEventPublisherAdapter(SaveOutboxEventPort saveOutboxEventPort,
                                                    ObjectMapper objectMapper) {
        this.saveOutboxEventPort = saveOutboxEventPort;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishRecoveryOpened(long recoveryId, long sellerId, BigDecimal amount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("recoveryId", recoveryId);
        payload.put("sellerId", sellerId);
        payload.put("amount", amount);
        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE, String.valueOf(recoveryId), "Opened", toJson(payload)));
    }

    @Override
    public void publishRecoveryOffset(long allocationId, Long recoveryId, long sellerId, BigDecimal amount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("allocationId", allocationId);
        if (recoveryId != null) {
            payload.put("recoveryId", recoveryId); // optional integer — 미상 시 생략(null 불가)
        }
        payload.put("sellerId", sellerId);
        payload.put("amount", amount);
        // aggregateId 는 상계 이력 id — append-only 배분 레코드와 1:1.
        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE, String.valueOf(allocationId), "Offset", toJson(payload)));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("seller_recovery 이벤트 직렬화 실패", e);
        }
    }
}
