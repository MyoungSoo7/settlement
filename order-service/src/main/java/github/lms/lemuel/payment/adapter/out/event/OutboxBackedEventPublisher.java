package github.lms.lemuel.payment.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.payment.application.port.out.PublishEventPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Payment 도메인의 이벤트를 outbox_events 테이블로 영속시키는 어댑터.
 *
 * <p>도메인 서비스의 @Transactional 안에서 호출되면 비즈니스 변경과
 * outbox 레코드가 같은 커밋으로 원자화된다 — Transactional Outbox 패턴.
 *
 * <p>실제 외부 발행은 {@code OutboxPublisherScheduler} 가 담당한다.
 */
@Component
public class OutboxBackedEventPublisher implements PublishEventPort {

    private static final Logger log = LoggerFactory.getLogger(OutboxBackedEventPublisher.class);
    private static final String AGGREGATE_TYPE = "Payment";

    private final SaveOutboxEventPort saveOutboxEventPort;
    private final ObjectMapper objectMapper;

    public OutboxBackedEventPublisher(SaveOutboxEventPort saveOutboxEventPort,
                                      ObjectMapper objectMapper) {
        this.saveOutboxEventPort = saveOutboxEventPort;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishPaymentCreated(Long paymentId, Long orderId) {
        writeOutbox(paymentId, "PaymentCreated", Map.of(
                "paymentId", paymentId,
                "orderId", orderId
        ));
    }

    @Override
    public void publishPaymentAuthorized(Long paymentId) {
        writeOutbox(paymentId, "PaymentAuthorized", Map.of(
                "paymentId", paymentId
        ));
    }

    @Override
    public void publishPaymentCaptured(Long paymentId, Long orderId, BigDecimal amount) {
        writeOutbox(paymentId, "PaymentCaptured", Map.of(
                "paymentId", paymentId,
                "orderId", orderId,
                "amount", amount.toPlainString()
        ));
    }

    @Override
    public void publishPaymentRefunded(Long paymentId, Long orderId) {
        writeOutbox(paymentId, "PaymentRefunded", Map.of(
                "paymentId", paymentId,
                "orderId", orderId
        ));
    }

    private void writeOutbox(Long paymentId, String eventType, Map<String, Object> payload) {
        // LinkedHashMap 으로 감싸 직렬화 순서 결정적 보장
        Map<String, Object> ordered = new LinkedHashMap<>(payload);
        String json;
        try {
            json = objectMapper.writeValueAsString(ordered);
        } catch (JsonProcessingException e) {
            // 직렬화 실패는 즉시 치명적 — 이벤트 손실보다 예외로 커밋 롤백이 안전
            throw new IllegalStateException("Failed to serialize outbox payload for " + eventType, e);
        }
        OutboxEvent event = OutboxEvent.pending(
                AGGREGATE_TYPE,
                String.valueOf(paymentId),
                eventType,
                json
        );
        saveOutboxEventPort.save(event);
        log.debug("Outbox write: type={}, aggregateId={}", eventType, paymentId);
    }
}
