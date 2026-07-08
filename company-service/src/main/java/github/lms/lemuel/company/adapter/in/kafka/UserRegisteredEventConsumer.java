package github.lms.lemuel.company.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventJpaEntity;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.company.application.port.out.SaveSellerPort;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * order 의 회원 등록 이벤트 수신 → 셀러 목록 적재 (ADR 0023 Phase 3 후속).
 *
 * <p>user.registered 페이로드에는 기업 연결 키가 없어(userId/email 만) 자동 매핑은 불가능하다 —
 * 이 컨슈머는 링크 대상 셀러 목록만 축적하고, 실제 셀러↔기업 링크는 admin 명시 링크로 맺는다.
 *
 * <p>멱등: processed_events(consumer_group, event_id) PK + sellerId UPSERT.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class UserRegisteredEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(UserRegisteredEventConsumer.class);
    private static final String CONSUMER_GROUP = "lemuel-company";

    private final SaveSellerPort saveSellerPort;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    public UserRegisteredEventConsumer(SaveSellerPort saveSellerPort,
                                       ProcessedEventRepository processedEventRepository,
                                       ObjectMapper objectMapper) {
        this.saveSellerPort = saveSellerPort;
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${app.kafka.topic.user-registered:lemuel.user.registered}", groupId = CONSUMER_GROUP)
    @Transactional
    public void onUserRegistered(ConsumerRecord<String, String> record, Acknowledgment ack) {
        UUID eventId = extractEventId(record);
        if (eventId == null) {
            log.warn("event_id 헤더 없는 레코드 스킵. topic={}, offset={}", record.topic(), record.offset());
            ack.acknowledge();
            return;
        }

        ProcessedEventJpaEntity.ProcessedEventId key =
                new ProcessedEventJpaEntity.ProcessedEventId(CONSUMER_GROUP, eventId);
        if (processedEventRepository.existsById(key)) {
            log.info("이미 처리된 이벤트 스킵. eventId={}", eventId);
            ack.acknowledge();
            return;
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(record.value());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("잘못된 JSON payload (DLT 대상). eventId={}, payload={}", eventId, record.value());
            throw new IllegalArgumentException("Invalid JSON payload, eventId=" + eventId, e);
        }
        if (!node.hasNonNull("userId")) {
            throw new IllegalArgumentException("userId 누락, eventId=" + eventId);
        }

        Long sellerId = node.get("userId").asLong();
        String email = node.hasNonNull("email") ? node.get("email").asText() : null;
        saveSellerPort.record(sellerId, email);

        processedEventRepository.save(new ProcessedEventJpaEntity(CONSUMER_GROUP, eventId, "UserRegistered"));
        log.info("셀러 적재. eventId={}, sellerId={}", eventId, sellerId);
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
