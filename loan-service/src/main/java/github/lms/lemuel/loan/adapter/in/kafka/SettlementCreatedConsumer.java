package github.lms.lemuel.loan.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventJpaEntity;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.loan.application.port.in.IngestSettlementUseCase;
import github.lms.lemuel.loan.application.port.in.IngestSettlementUseCase.IngestSettlementCommand;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

/**
 * settlement 의 정산 생성 이벤트 수신 → 로컬 정산 뷰 적재 컨슈머.
 *
 * <p>loan-service 는 settlements 테이블을 직접 읽을 수 없으므로(DB-per-service),
 * 이 컨슈머가 셀러별 미지급 정산예정금(담보)을 자체 DB 로 materialize 한다.
 *
 * <p>멱등 방어: processed_events(consumer_group, event_id) PK + 뷰 settlementId UPSERT.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class SettlementCreatedConsumer {

    private static final Logger log = LoggerFactory.getLogger(SettlementCreatedConsumer.class);
    private static final String CONSUMER_GROUP = "lemuel-loan";

    private final IngestSettlementUseCase ingestSettlementUseCase;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    public SettlementCreatedConsumer(IngestSettlementUseCase ingestSettlementUseCase,
                                     ProcessedEventRepository processedEventRepository,
                                     ObjectMapper objectMapper) {
        this.ingestSettlementUseCase = ingestSettlementUseCase;
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${app.kafka.topic.settlement-created}", groupId = CONSUMER_GROUP)
    @Transactional
    public void onSettlementCreated(ConsumerRecord<String, String> record, Acknowledgment ack) {
        UUID eventId = extractEventId(record);
        if (eventId == null) {
            log.warn("event_id 헤더 없는 레코드 스킵. topic={}, offset={}", record.topic(), record.offset());
            ack.acknowledge();
            return;
        }

        ProcessedEventJpaEntity.ProcessedEventId key =
                new ProcessedEventJpaEntity.ProcessedEventId(CONSUMER_GROUP, eventId);
        if (processedEventRepository.existsById(key)) {
            log.info("이미 처리된 이벤트 스킵. eventId={}", eventId);
            ack.acknowledge();
            return;
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(record.value());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("잘못된 JSON payload (DLT 대상). eventId={}, payload={}", eventId, record.value());
            throw new IllegalArgumentException("Invalid JSON payload, eventId=" + eventId, e);
        }

        IngestSettlementCommand command = new IngestSettlementCommand(
                node.get("settlementId").asLong(),
                node.get("sellerId").asLong(),
                new BigDecimal(node.get("amount").asText()),
                node.hasNonNull("dueDate") ? LocalDate.parse(node.get("dueDate").asText()) : null);

        ingestSettlementUseCase.ingest(command);

        processedEventRepository.save(new ProcessedEventJpaEntity(CONSUMER_GROUP, eventId, "SettlementCreated"));

        log.info("로컬 정산뷰 적재. eventId={}, settlementId={}", eventId, command.settlementId());
        ack.acknowledge();
    }

    private static UUID extractEventId(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader("event_id");
        if (header == null) return null;
        try {
            return UUID.fromString(new String(header.value(), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
