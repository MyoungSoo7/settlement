package github.lms.lemuel.loan.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.loan.application.port.in.IngestSettlementUseCase;
import github.lms.lemuel.loan.application.port.in.IngestSettlementUseCase.IngestSettlementCommand;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * settlement 의 정산 생성 이벤트 수신 → 로컬 정산 뷰 적재 컨슈머.
 *
 * <p>loan-service 는 settlements 테이블을 직접 읽을 수 없으므로(DB-per-service),
 * 이 컨슈머가 셀러별 미지급 정산예정금(담보)을 자체 DB 로 materialize 한다.
 *
 * <p>멱등 골격(헤더추출·(consumer_group, event_id) 멱등·JSON 파싱·마커저장·ack)은
 * {@link IdempotentEventConsumer} 가 소유하고, 여기서는 커맨드 매핑·use case 호출만 구현한다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class SettlementCreatedConsumer extends IdempotentEventConsumer {

    private static final String CONSUMER_GROUP = "lemuel-loan";

    private final IngestSettlementUseCase ingestSettlementUseCase;

    public SettlementCreatedConsumer(IngestSettlementUseCase ingestSettlementUseCase,
                                     ProcessedEventRepository processedEventRepository,
                                     ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.ingestSettlementUseCase = ingestSettlementUseCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.settlement-created}", groupId = CONSUMER_GROUP)
    @Transactional
    public void onSettlementCreated(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return CONSUMER_GROUP;
    }

    @Override
    protected String eventType() {
        return "SettlementCreated";
    }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        IngestSettlementCommand command = new IngestSettlementCommand(
                node.get("settlementId").asLong(),
                node.get("sellerId").asLong(),
                new BigDecimal(node.get("amount").asText()),
                node.hasNonNull("dueDate") ? LocalDate.parse(node.get("dueDate").asText()) : null);

        ingestSettlementUseCase.ingest(command);

        log.info("로컬 정산뷰 적재. eventId={}, settlementId={}", eventId, command.settlementId());
    }
}
