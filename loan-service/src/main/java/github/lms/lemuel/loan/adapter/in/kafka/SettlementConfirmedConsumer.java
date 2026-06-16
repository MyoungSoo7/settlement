package github.lms.lemuel.loan.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventJpaEntity;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.loan.application.port.in.ApplyRepaymentUseCase;
import github.lms.lemuel.loan.application.port.in.ApplyRepaymentUseCase.ApplyRepaymentCommand;
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
import java.util.UUID;

/**
 * settlement 의 정산 확정 이벤트 수신 → 상환 차감 트리거 컨슈머 (상환 saga 의 loan 측 진입점).
 *
 * <p>멱등: processed_events(consumer_group, event_id) + loan_repayments.settlement_id UNIQUE.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class SettlementConfirmedConsumer {

    private static final Logger log = LoggerFactory.getLogger(SettlementConfirmedConsumer.class);
    private static final String CONSUMER_GROUP = "lemuel-loan";

    private final ApplyRepaymentUseCase applyRepaymentUseCase;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    public SettlementConfirmedConsumer(ApplyRepaymentUseCase applyRepaymentUseCase,
                                       ProcessedEventRepository processedEventRepository,
                                       ObjectMapper objectMapper) {
        this.applyRepaymentUseCase = applyRepaymentUseCase;
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${app.kafka.topic.settlement-confirmed}", groupId = CONSUMER_GROUP)
    @Transactional
    public void onSettlementConfirmed(ConsumerRecord<String, String> record, Acknowledgment ack) {
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

        ApplyRepaymentCommand command = new ApplyRepaymentCommand(
                node.get("settlementId").asLong(),
                node.get("sellerId").asLong(),
                new BigDecimal(node.get("amount").asText()));

        applyRepaymentUseCase.apply(command);

        processedEventRepository.save(new ProcessedEventJpaEntity(CONSUMER_GROUP, eventId, "SettlementConfirmed"));

        log.info("상환 차감 트리거 완료. eventId={}, settlementId={}", eventId, command.settlementId());
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
