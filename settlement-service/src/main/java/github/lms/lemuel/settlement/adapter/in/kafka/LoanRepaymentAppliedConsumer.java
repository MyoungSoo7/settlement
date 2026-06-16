package github.lms.lemuel.settlement.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventJpaEntity;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.settlement.application.port.in.ApplyLoanDeductionUseCase;
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
 * loan-service 의 상환 차감 완료 이벤트 수신 → settlement 측 차감액 반영 (상환 saga 의 settlement 종착점).
 *
 * <p>정산건별 차감액을 보존해, 해당 정산 payout 시 순지급액(netAmount - deducted)으로 지급되게 한다.
 * 차감 0(대출 없는 셀러)도 정상 수신·기록된다.
 *
 * <p>멱등: processed_events(consumer_group, event_id) + settlement_loan_deductions.settlement_id PK.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class LoanRepaymentAppliedConsumer {

    private static final Logger log = LoggerFactory.getLogger(LoanRepaymentAppliedConsumer.class);
    private static final String CONSUMER_GROUP = "lemuel-settlement";

    private final ApplyLoanDeductionUseCase applyLoanDeductionUseCase;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    public LoanRepaymentAppliedConsumer(ApplyLoanDeductionUseCase applyLoanDeductionUseCase,
                                        ProcessedEventRepository processedEventRepository,
                                        ObjectMapper objectMapper) {
        this.applyLoanDeductionUseCase = applyLoanDeductionUseCase;
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${app.kafka.topic.loan-repayment-applied}", groupId = CONSUMER_GROUP)
    @Transactional
    public void onLoanRepaymentApplied(ConsumerRecord<String, String> record, Acknowledgment ack) {
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

        long settlementId = node.get("settlementId").asLong();
        long sellerId = node.get("sellerId").asLong();
        BigDecimal deducted = new BigDecimal(node.get("deducted").asText());

        applyLoanDeductionUseCase.apply(settlementId, sellerId, deducted);

        processedEventRepository.save(new ProcessedEventJpaEntity(CONSUMER_GROUP, eventId, "LoanRepaymentApplied"));

        log.info("loan 차감 반영 완료. eventId={}, settlementId={}, deducted={}", eventId, settlementId, deducted);
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
