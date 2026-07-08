package github.lms.lemuel.loan.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.loan.application.port.in.IngestCompanyReputationUseCase;
import github.lms.lemuel.loan.application.port.in.IngestCompanyReputationUseCase.IngestCompanyReputationCommand;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * company 의 평판 등급 변동 이벤트 수신 → 로컬 평판 프로젝션 적재 컨슈머 (ADR 0023 Phase 3).
 *
 * <p>loan-service 는 company_db 를 직접 읽을 수 없으므로(DB-per-service), 이 컨슈머가 셀러(법인)의
 * 평판 리스크 신호를 자체 DB 로 materialize 한다 — 여신 심사 참고 지표.
 *
 * <p>멱등 골격은 {@link IdempotentEventConsumer} 가 소유하고(processed_events + stockCode UPSERT),
 * 여기서는 커맨드 매핑·use case 호출만 구현한다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class CompanyReputationChangedConsumer extends IdempotentEventConsumer {

    private static final String CONSUMER_GROUP = "lemuel-loan";

    private final IngestCompanyReputationUseCase ingestCompanyReputationUseCase;

    public CompanyReputationChangedConsumer(IngestCompanyReputationUseCase ingestCompanyReputationUseCase,
                                            ProcessedEventRepository processedEventRepository,
                                            ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.ingestCompanyReputationUseCase = ingestCompanyReputationUseCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.company-reputation-changed}", groupId = CONSUMER_GROUP)
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
        List<Long> sellerIds = new ArrayList<>();
        if (node.hasNonNull("sellerIds") && node.get("sellerIds").isArray()) {
            node.get("sellerIds").forEach(n -> sellerIds.add(n.asLong()));
        }

        IngestCompanyReputationCommand command = new IngestCompanyReputationCommand(
                node.get("stockCode").asText(),
                node.get("score").asInt(),
                node.get("grade").asText(),
                node.hasNonNull("previousGrade") ? node.get("previousGrade").asText() : null,
                LocalDate.parse(node.get("snapshotDate").asText()),
                sellerIds);

        ingestCompanyReputationUseCase.ingest(command);

        log.info("평판 프로젝션 적재. eventId={}, stockCode={}, grade={}",
                eventId, command.stockCode(), command.grade());
    }
}
