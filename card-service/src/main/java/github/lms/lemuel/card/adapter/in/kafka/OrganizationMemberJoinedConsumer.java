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
 * 초대 수락으로 멤버가 조직에 합류(ACTIVE)한 이벤트 수신 → 멤버 프로젝션에 반영.
 * 카드 발급/권한 판정이 이 프로젝션을 근거로 하므로, 합류를 놓치면 정당한 멤버가 카드를
 * 신청·보유할 수 없다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class OrganizationMemberJoinedConsumer extends IdempotentEventConsumer {

    private static final String CONSUMER_GROUP = "lemuel-card";

    private final IngestOrgProjectionUseCase useCase;

    public OrganizationMemberJoinedConsumer(IngestOrgProjectionUseCase useCase,
                                            ProcessedEventRepository processedEventRepository,
                                            ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.useCase = useCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.organization-member-joined}",
            groupId = CONSUMER_GROUP, containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onMemberJoined(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return CONSUMER_GROUP;
    }

    @Override
    protected String eventType() {
        return "OrganizationMemberJoined";
    }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        long organizationId = requiredLong(node, "organizationId", eventId);
        long userId = requiredLong(node, "userId", eventId);
        String role = requiredText(node, "role", eventId);
        long membershipId = requiredLong(node, "membershipId", eventId);

        useCase.upsertMember(new MemberCommand(organizationId, userId, role, membershipId));

        log.info("멤버 합류 반영. eventId={}, orgId={}, userId={}, role={}",
                eventId, organizationId, userId, role);
    }
}
