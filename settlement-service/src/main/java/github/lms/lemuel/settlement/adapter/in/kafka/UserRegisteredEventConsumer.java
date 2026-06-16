package github.lms.lemuel.settlement.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventJpaEntity;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementUserViewJpaEntity;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementUserViewRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * UserRegistered 이벤트 → settlement 소유 사용자 프로젝션(settlement_user_view) 적재 (ADR 0020 Phase 3b).
 *
 * <p>order users(email)를 @Immutable 매핑하던 read-model 을 이벤트 기반 로컬 프로젝션으로 대체.
 * (consumer_group, event_id) 멱등.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class UserRegisteredEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(UserRegisteredEventConsumer.class);
    private static final String CONSUMER_GROUP = "lemuel-settlement";

    private final SettlementUserViewRepository userViewRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    public UserRegisteredEventConsumer(SettlementUserViewRepository userViewRepository,
                                       ProcessedEventRepository processedEventRepository,
                                       ObjectMapper objectMapper) {
        this.userViewRepository = userViewRepository;
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${app.kafka.topic.user-registered:lemuel.user.registered}",
            groupId = CONSUMER_GROUP,
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onUserRegistered(ConsumerRecord<String, String> record, Acknowledgment ack) {
        UUID eventId = extractEventId(record);
        if (eventId == null) {
            log.warn("Skipping user event without event_id header. topic={}, offset={}",
                    record.topic(), record.offset());
            ack.acknowledge();
            return;
        }

        ProcessedEventJpaEntity.ProcessedEventId key =
                new ProcessedEventJpaEntity.ProcessedEventId(CONSUMER_GROUP, eventId);
        if (processedEventRepository.existsById(key)) {
            log.info("User event already processed, skipping. eventId={}", eventId);
            ack.acknowledge();
            return;
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(record.value());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON payload, eventId=" + eventId, e);
        }

        if (!node.hasNonNull("userId")) {
            throw new IllegalArgumentException("Missing userId, eventId=" + eventId);
        }
        Long userId = node.get("userId").asLong();

        SettlementUserViewJpaEntity view = userViewRepository.findById(userId)
                .orElseGet(SettlementUserViewJpaEntity::new);
        view.setUserId(userId);
        view.setEmail(node.hasNonNull("email") ? node.get("email").asText() : null);
        view.setUpdatedAt(LocalDateTime.now());
        userViewRepository.save(view);

        processedEventRepository.save(new ProcessedEventJpaEntity(CONSUMER_GROUP, eventId, "UserRegistered"));
        log.info("settlement_user_view upserted. eventId={}, userId={}", eventId, userId);
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
