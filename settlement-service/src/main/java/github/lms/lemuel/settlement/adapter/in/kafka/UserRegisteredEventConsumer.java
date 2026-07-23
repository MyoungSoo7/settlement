package github.lms.lemuel.settlement.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ConsumedEventQuarantine;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementUserViewJpaEntity;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementUserViewRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * UserRegistered 이벤트 → settlement 소유 사용자 프로젝션(settlement_user_view) 적재 (ADR 0020 Phase 3b).
 *
 * <p>order users(email)를 @Immutable 매핑하던 read-model 을 이벤트 기반 로컬 프로젝션으로 대체.
 * 멱등 골격은 {@link IdempotentEventConsumer} 가 소유하고, 여기서는 뷰 매핑만 구현한다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class UserRegisteredEventConsumer extends IdempotentEventConsumer {

    private static final String CONSUMER_GROUP = "lemuel-settlement";

    private final SettlementUserViewRepository userViewRepository;
    private final SettlementProjectionMetrics projectionMetrics;

    public UserRegisteredEventConsumer(SettlementUserViewRepository userViewRepository,
                                       ProcessedEventRepository processedEventRepository,
                                       ObjectMapper objectMapper,
                                       SettlementProjectionMetrics projectionMetrics,
                                       ConsumedEventQuarantine quarantine) {
        super(processedEventRepository, objectMapper, quarantine);
        this.userViewRepository = userViewRepository;
        this.projectionMetrics = projectionMetrics;
    }

    @KafkaListener(
            topics = "${app.kafka.topic.user-registered:lemuel.user.registered}",
            groupId = CONSUMER_GROUP,
            containerFactory = "kafkaListenerContainerFactory"
    )
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
        Long userId = requiredLong(node, "userId", eventId);

        SettlementUserViewJpaEntity view = userViewRepository.findById(userId)
                .orElseGet(SettlementUserViewJpaEntity::new);
        view.setUserId(userId);
        view.setEmail(node.hasNonNull("email") ? node.get("email").asText() : null);
        view.setUpdatedAt(LocalDateTime.now());
        userViewRepository.save(view);

        log.info("settlement_user_view upserted. eventId={}, userId={}", eventId, userId);
    }

    @Override
    protected void afterProcessed(ConsumerRecord<String, String> record) {
        projectionMetrics.recordApply("user", record.timestamp());
    }
}
