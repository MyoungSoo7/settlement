package github.lms.lemuel.payout.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.payout.application.port.out.PublishPayoutEventPort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Payout 도메인 이벤트를 Transactional Outbox 에 기록한다. 지급 완료 상태 저장 트랜잭션과 같은
 * 트랜잭션(PayoutTxSteps.markCompleted, REQUIRES_NEW)에서 저장되어 원자성이 보장되고,
 * shared-common 의 OutboxPublisherScheduler 가 Kafka 로 발행한다.
 *
 * <p>aggregateType="Payout", eventType="PayoutCompleted" → 토픽 lemuel.payout.completed (account 구독).
 * settlement 계열 규약대로 amount 는 JSON number 로 직렬화된다.
 */
@Component
public class PayoutKafkaEventPublisherAdapter implements PublishPayoutEventPort {

    private static final String AGGREGATE_TYPE = "Payout";
    private static final String EVENT_TYPE = "PayoutCompleted";

    private final SaveOutboxEventPort saveOutboxEventPort;
    private final ObjectMapper objectMapper;

    public PayoutKafkaEventPublisherAdapter(SaveOutboxEventPort saveOutboxEventPort,
                                            ObjectMapper objectMapper) {
        this.saveOutboxEventPort = saveOutboxEventPort;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishPayoutCompleted(long payoutId, Long settlementId, long sellerId, BigDecimal amount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payoutId", payoutId);
        payload.put("settlementId", settlementId); // 수동 송금은 null
        payload.put("sellerId", sellerId);
        payload.put("amount", amount);
        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE, String.valueOf(payoutId), EVENT_TYPE, toJson(payload)));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("payout 이벤트 직렬화 실패", e);
        }
    }
}
