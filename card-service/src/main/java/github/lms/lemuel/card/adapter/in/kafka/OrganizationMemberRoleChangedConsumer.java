package github.lms.lemuel.card.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.card.application.port.in.IngestOrgProjectionUseCase;
import github.lms.lemuel.card.application.port.in.IngestOrgProjectionUseCase.MemberCommand;
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
 * 멤버 역할 변경 수신 → 멤버 프로젝션의 역할 갱신(newRole 기준 upsert).
 * previousRole 은 검증·감사용 정보라 프로젝션에는 최신 역할만 남긴다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class OrganizationMemberRoleChangedConsumer extends IdempotentEventConsumer {

    private static final String CONSUMER_GROUP = "lemuel-card";

    private final IngestOrgProjectionUseCase useCase;

    public OrganizationMemberRoleChangedConsumer(IngestOrgProjectionUseCase useCase,
                                                 ProcessedEventRepository processedEventRepository,
                                                 ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.useCase = useCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.organization-member-role-changed}",
            groupId = CONSUMER_GROUP, containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onMemberRoleChanged(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return CONSUMER_GROUP;
    }

    @Override
    protected String eventType() {
        return "OrganizationMemberRoleChanged";
    }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        long organizationId = requiredLong(node, "organizationId", eventId);
        long userId = requiredLong(node, "userId", eventId);
        useCase.upsertMember(new MemberCommand(organizationId, userId, requiredText(node, "newRole", eventId)));
        log.info("멤버 역할 갱신. eventId={}, orgId={}, userId={}", eventId, organizationId, userId);
    }
}
