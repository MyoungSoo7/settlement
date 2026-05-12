package github.lms.lemuel.settlement.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventJpaEntity;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.settlement.application.port.in.CreateSettlementFromPaymentUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 결제 완료 이벤트 수신 → 정산 자동 생성 컨슈머.
 *
 * <p>이 컨슈머가 추가됨으로써 "배치 중심 정산" 구조가 "이벤트 기반 정산" 으로 전환된다.
 *
 * <p>멱등성 3 단 방어:
 *   1. outbox event_id UUID unique — 프로듀서 측 중복 발행 방지
 *   2. processed_events(consumer_group, event_id) PK — 컨슈머 측 재수신 방지
 *   3. settlements.payment_id UNIQUE (V3) — 스키마 수준 최종 방어
 *
 * <p>장애 처리 (DLT):
 * <ul>
 *   <li>예외가 throw 되면 ack 하지 않고 {@link KafkaErrorHandlerConfig} 의 DefaultErrorHandler 로 위임.</li>
 *   <li>일시적 예외 → ExponentialBackOff(2s ×2, 3회) 재시도 → 끝나면 DLT</li>
 *   <li>독성 메시지 (JsonProcessingException, IllegalArgumentException, IllegalStateException)
 *       → 재시도 없이 즉시 DLT — 같은 파티션의 후속 메시지 stall 방지</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class PaymentEventKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventKafkaConsumer.class);
    private static final String CONSUMER_GROUP = "lemuel-settlement";

    private final CreateSettlementFromPaymentUseCase createSettlementFromPaymentUseCase;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    public PaymentEventKafkaConsumer(CreateSettlementFromPaymentUseCase createSettlementFromPaymentUseCase,
                                     ProcessedEventRepository processedEventRepository,
                                     ObjectMapper objectMapper) {
        this.createSettlementFromPaymentUseCase = createSettlementFromPaymentUseCase;
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${app.kafka.topic.payment-captured}",
            groupId = CONSUMER_GROUP,
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onPaymentCaptured(ConsumerRecord<String, String> record, Acknowledgment ack) {
        UUID eventId = extractEventId(record);
        if (eventId == null) {
            log.warn("Skipping record without event_id header. topic={}, offset={}",
                    record.topic(), record.offset());
            ack.acknowledge();
            return;
        }

        // 1. 컨슈머 멱등 체크: (group, event_id) 이미 처리되었으면 스킵
        ProcessedEventJpaEntity.ProcessedEventId key =
                new ProcessedEventJpaEntity.ProcessedEventId(CONSUMER_GROUP, eventId);
        if (processedEventRepository.existsById(key)) {
            log.info("Event already processed, skipping. eventId={}", eventId);
            ack.acknowledge();
            return;
        }

        // 2. 페이로드 파싱 — JsonProcessingException 은 즉시 DLT (재시도 무의미)
        JsonNode node;
        try {
            node = objectMapper.readTree(record.value());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Invalid JSON payload (will go to DLT). eventId={}, payload={}", eventId, record.value());
            throw new IllegalArgumentException("Invalid JSON payload, eventId=" + eventId, e);
        }

        Long paymentId = node.get("paymentId").asLong();
        Long orderId = node.get("orderId").asLong();

        // 3. 금액은 이벤트에 없으면 조회해야 하지만, 정산 서비스가 payment_id 로 조회 가능 —
        //    이벤트 스키마 확장 시 amount 를 outbox 페이로드에 포함시키는 것이 더 안전.
        //    현재 단순화를 위해 관례적으로 payload 의 amount 필드 존재 여부로 분기.
        BigDecimal amount = node.has("amount")
                ? new BigDecimal(node.get("amount").asText())
                : BigDecimal.ZERO;  // 0 이면 정산 서비스가 이후 보강 책임

        // 4. 정산 생성 (내부에서 payment_id unique 로 추가 중복 방어)
        //    — 도메인 예외(IllegalArgumentException/IllegalStateException) 는 DefaultErrorHandler 가
        //    재시도 없이 즉시 DLT 로 라우팅. 일시적 예외(DB 락, IO) 는 ExponentialBackOff 재시도.
        createSettlementFromPaymentUseCase.createSettlementFromPayment(paymentId, orderId, amount);

        // 5. 처리 기록 — 재처리 시 (group, event_id) 멱등 보장
        processedEventRepository.save(new ProcessedEventJpaEntity(
                CONSUMER_GROUP,
                eventId,
                "PaymentCaptured"
        ));

        log.info("Settlement created from Kafka event. eventId={}, paymentId={}", eventId, paymentId);
        ack.acknowledge();
    }

    private static UUID extractEventId(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader("event_id");
        if (header == null) return null;
        try {
            return UUID.fromString(new String(header.value(), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
