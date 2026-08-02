package github.lms.lemuel.card.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.card.application.port.in.IngestOrgProjectionUseCase;
import github.lms.lemuel.card.application.port.in.IngestOrgProjectionUseCase.MemberCommand;
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
 * 조직 생성 수신 → 조직 프로젝션 적재 (+ 생성자를 OWNER 멤버로 등록).
 *
 * <p><b>type 필터:</b> {@code type != SELLER} 면 무시한다(브리프 리졸루션 #1) — CORPORATE 조직은
 * 1단계 카드 발급 대상이 아니다. 무시는 정상 흐름이라 예외를 던지지 않고 로그만 남긴다.
 *
 * <p><b>오너 멤버 등록:</b> organization-service 는 조직 생성 시 {@code OrganizationCreated} 만
 * 발행하고, 오너용 {@code member_joined} 를 별도로 발행하지 않는다(초대 수락 전용 이벤트라서다 —
 * {@code OrganizationCommandService} 확인). 그래서 이 컨슈머가 오너 멤버 등록까지 하지 않으면
 * 조직 생성자가 영구히 멤버 프로젝션에 없는 상태로 남는다. 이벤트 스키마 설명이 {@code ownerUserId}를
 * "생성자(자동 OWNER)"라고 명시한 것도 바로 이 목적이다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class OrganizationCreatedConsumer extends IdempotentEventConsumer {

    private static final String CONSUMER_GROUP = "lemuel-card";
    private static final String SELLER_TYPE = "SELLER";

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
    public void onOrganizationCreated(ConsumerRecord<String, String> record, Acknowledgment ack) {
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
        long organizationId = requiredLong(node, "organizationId", eventId);
        String name = requiredText(node, "name", eventId);
        String type = requiredText(node, "type", eventId);
        long ownerUserId = requiredLong(node, "ownerUserId", eventId);
        String externalRef = node.hasNonNull("externalRef") ? node.get("externalRef").asText() : null;

        if (!SELLER_TYPE.equals(type)) {
            log.info("CORPORATE 조직 무시(1단계 카드 발급 대상 아님). eventId={}, orgId={}, type={}",
                    eventId, organizationId, type);
            return;
        }

        useCase.createOrg(new OrgCommand(organizationId, name, type, externalRef));
        // created 페이로드에는 membershipId 가 없다 — null 세대는 비교에서 항상 열위라
        // 이미 제거된 오너를 부활시키지 못한다(늦게 도착한 created 재전송 방어).
        useCase.upsertMember(new MemberCommand(organizationId, ownerUserId, "OWNER", null));

        log.info("조직 프로젝션 적재 완료. eventId={}, orgId={}, ownerUserId={}",
                eventId, organizationId, ownerUserId);
    }
}
