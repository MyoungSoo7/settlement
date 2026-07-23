package github.lms.lemuel.company.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.company.application.port.out.SaveSellerPort;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * order 의 회원 등록 이벤트 수신 → 셀러 목록 적재 (ADR 0023 Phase 3 후속).
 *
 * <p>user.registered 페이로드에는 기업 연결 키가 없어(userId/email 만) 자동 매핑은 불가능하다 —
 * 이 컨슈머는 링크 대상 셀러 목록만 축적하고, 실제 셀러↔기업 링크는 admin 명시 링크로 맺는다.
 *
 * <p>멱등 골격은 {@link IdempotentEventConsumer} 가 소유하고(processed_events + sellerId UPSERT),
 * 여기서는 셀러 적재만 구현한다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class UserRegisteredEventConsumer extends IdempotentEventConsumer {

    private static final String CONSUMER_GROUP = "lemuel-company";

    private final SaveSellerPort saveSellerPort;

    public UserRegisteredEventConsumer(SaveSellerPort saveSellerPort,
                                       ProcessedEventRepository processedEventRepository,
                                       ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.saveSellerPort = saveSellerPort;
    }

    @KafkaListener(topics = "${app.kafka.topic.user-registered:lemuel.user.registered}", groupId = CONSUMER_GROUP, containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onUserRegistered(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return CONSUMER_GROUP;
    }

    @Override
    protected String eventType() {
        return "UserRegistered";
    }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        if (!node.hasNonNull("userId")) {
            throw new IllegalArgumentException("userId 누락, eventId=" + eventId);
        }

        Long sellerId = node.get("userId").asLong();
        String email = node.hasNonNull("email") ? node.get("email").asText() : null;
        saveSellerPort.record(sellerId, email);

        log.info("셀러 적재. eventId={}, sellerId={}", eventId, sellerId);
    }
}
