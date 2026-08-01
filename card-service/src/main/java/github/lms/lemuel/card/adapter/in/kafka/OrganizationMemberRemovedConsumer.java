package github.lms.lemuel.card.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.card.application.port.in.IngestOrgProjectionUseCase;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 조직 이탈 수신 → 멤버 프로젝션 비활성화 + 해당 임직원 카드 자동 정지(Task 12).
 *
 * <p>이 컨슈머가 없으면 조직에서 제거된 임직원의 카드가 유효한 채로 남는다.
 * 멱등: 이미 정지된 카드를 다시 정지해도 무해하다(Card#suspend 는 멱등).
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class OrganizationMemberRemovedConsumer extends IdempotentEventConsumer {

    private static final String CONSUMER_GROUP = "lemuel-card";

    private final IngestOrgProjectionUseCase useCase;

    public OrganizationMemberRemovedConsumer(IngestOrgProjectionUseCase useCase,
                                             ProcessedEventRepository processedEventRepository,
                                             ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.useCase = useCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.organization-member-removed}",
            groupId = CONSUMER_GROUP, containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onMemberRemoved(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return CONSUMER_GROUP;
    }

    @Override
    protected String eventType() {
        return "OrganizationMemberRemoved";
    }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        long organizationId = requiredLong(node, "organizationId", eventId);
        long userId = requiredLong(node, "userId", eventId);
        useCase.removeMember(organizationId, userId);
        log.info("조직 이탈 반영 — 카드 정지 대상. eventId={}, orgId={}, userId={}",
                eventId, organizationId, userId);
    }
}
