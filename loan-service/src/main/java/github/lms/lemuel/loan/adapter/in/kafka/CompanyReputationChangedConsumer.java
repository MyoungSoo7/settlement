package github.lms.lemuel.loan.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventJpaEntity;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.loan.application.port.in.IngestCompanyReputationUseCase;
import github.lms.lemuel.loan.application.port.in.IngestCompanyReputationUseCase.IngestCompanyReputationCommand;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
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
 * <p>멱등 방어: processed_events(consumer_group, event_id) PK + 프로젝션 stockCode UPSERT.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class CompanyReputationChangedConsumer {

    private static final Logger log = LoggerFactory.getLogger(CompanyReputationChangedConsumer.class);
    private static final String CONSUMER_GROUP = "lemuel-loan";

    private final IngestCompanyReputationUseCase ingestCompanyReputationUseCase;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    public CompanyReputationChangedConsumer(IngestCompanyReputationUseCase ingestCompanyReputationUseCase,
                                            ProcessedEventRepository processedEventRepository,
                                            ObjectMapper objectMapper) {
        this.ingestCompanyReputationUseCase = ingestCompanyReputationUseCase;
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${app.kafka.topic.company-reputation-changed}", groupId = CONSUMER_GROUP)
    @Transactional
    public void onReputationChanged(ConsumerRecord<String, String> record, Acknowledgment ack) {
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

        processedEventRepository.save(
                new ProcessedEventJpaEntity(CONSUMER_GROUP, eventId, "CompanyReputationChanged"));

        log.info("평판 프로젝션 적재. eventId={}, stockCode={}, grade={}",
                eventId, command.stockCode(), command.grade());
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
