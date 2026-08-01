package github.lms.lemuel.card.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.card.application.port.in.IngestReputationUseCase;
import github.lms.lemuel.card.application.port.in.IngestReputationUseCase.ReputationCommand;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * company 의 평판 등급 변동 이벤트 수신 → 셀러별 평판 프로젝션 적재.
 *
 * <p><b>sellerId 매핑(브리프 리졸루션 #4 확인 결과):</b> {@code lemuel.company.reputation_changed}
 * 스키마에는 단일 {@code sellerId} 필드가 없다. 식별자는 {@code stockCode}(기업)이고,
 * {@code sellerIds}(정수 배열, 기업에 링크된 셀러 목록, 없으면 빈 배열)만 동봉된다 — 정본 샘플
 * ({@code sellerIds: [777, 1001]})과 스키마 설명("loan 의 셀러별 haircut 반영 키")으로 직접 확인했다.
 * card-service 의 {@code reputation_projection.seller_id} 는 VARCHAR PK 이므로, 배열의 각 원소를
 * {@code String.valueOf()} 로 변환해 개별 UPSERT 한다. 이 팬아웃 방식은 loan-service 의 동일 이벤트
 * 소비 선례(CompanyReputationService.ingest → SaveSellerReputationPort.upsert, ADR 0023 Phase 3)를
 * 그대로 따른 것이다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class CompanyReputationChangedConsumer extends IdempotentEventConsumer {

    private static final String CONSUMER_GROUP = "lemuel-card";

    private final IngestReputationUseCase useCase;

    public CompanyReputationChangedConsumer(IngestReputationUseCase useCase,
                                            ProcessedEventRepository processedEventRepository,
                                            ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.useCase = useCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.company-reputation-changed}",
            groupId = CONSUMER_GROUP, containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onReputationChanged(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return CONSUMER_GROUP;
    }

    @Override
    protected String eventType() {
        return "CompanyReputationChanged";
    }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        String grade = requiredText(node, "grade", eventId);

        List<Long> sellerIds = new ArrayList<>();
        JsonNode sellerIdsNode = node.get("sellerIds");
        if (sellerIdsNode != null && sellerIdsNode.isArray()) {
            sellerIdsNode.forEach(n -> sellerIds.add(n.asLong()));
        }

        useCase.ingest(new ReputationCommand(sellerIds, grade));

        log.info("평판 프로젝션 적재 완료. eventId={}, grade={}, sellerCount={}",
                eventId, grade, sellerIds.size());
    }
}
