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
 * 조직 이탈 수신 → 멤버 프로젝션 비활성화. 카드 자동 정지는 Task 12 가
 * {@code IngestOrgProjectionUseCase.removeMember} 호출 지점에 이어붙인다(브리프 리졸루션 #2) —
 * 이 태스크에서 카드를 직접 건드리면 Task 12 구현과 충돌한다.
 *
 * <p>이 컨슈머가 없으면(또는 지연되면) 조직에서 제거된 임직원의 카드가 유효한 채로 남는다 —
 * 이 태스크 전체가 막으려는 실패 모드.
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
        log.info("조직 이탈 반영 — 멤버 프로젝션 비활성화. eventId={}, orgId={}, userId={}",
                eventId, organizationId, userId);
    }
}
