package github.lms.lemuel.card.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.card.application.port.in.IngestOrgProjectionUseCase;
import github.lms.lemuel.card.application.port.in.IngestOrgProjectionUseCase.OrgCommand;
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
 * 조직 생성 수신 → 조직 프로젝션 + 소유자 OWNER 멤버십 적재.
 *
 * <p>CORPORATE 조직은 1단계 카드 대상이 아니므로 무시한다(멱등 마커는 남겨 재수신을 차단).
 * created 는 소유자의 member_joined 를 따로 발행하지 않으므로 소유자 적재는 유스케이스가 책임진다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class OrganizationCreatedConsumer extends IdempotentEventConsumer {

    private static final String CONSUMER_GROUP = "lemuel-card";

    private final IngestOrgProjectionUseCase useCase;

    public OrganizationCreatedConsumer(IngestOrgProjectionUseCase useCase,
                                       ProcessedEventRepository processedEventRepository,
                                       ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.useCase = useCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.organization-created}",
            groupId = CONSUMER_GROUP, containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onCreated(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return CONSUMER_GROUP;
    }

    @Override
    protected String eventType() {
        return "OrganizationCreated";
    }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        String type = requiredText(node, "type", eventId);
        long organizationId = requiredLong(node, "organizationId", eventId);
        if (!"SELLER".equals(type)) {
            log.info("SELLER 아님 — 카드 대상 외 조직 스킵. eventId={}, orgId={}, type={}",
                    eventId, organizationId, type);
            return;
        }
        useCase.registerOrg(new OrgCommand(
                organizationId,
                requiredText(node, "name", eventId),
                type,
                node.hasNonNull("externalRef") ? node.get("externalRef").asText() : null,
                requiredLong(node, "ownerUserId", eventId)));
        log.info("조직 프로젝션 적재. eventId={}, orgId={}", eventId, organizationId);
    }
}
