package github.lms.lemuel.account.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.account.application.port.in.RecordAccountEntryUseCase;
import github.lms.lemuel.account.domain.AccountEntry;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * settlement.created → 즉시분·유보분 <b>2전표</b> 적재(ADR 0026 Option ①, compound 금지).
 *
 * <ul>
 *   <li>즉시지급분 I = amount(net) − holdbackAmount → DR CASH / CR SELLER_PAYABLE (refType SETTLEMENT_CREATED)</li>
 *   <li>유보분 H = holdbackAmount → DR CASH / CR HOLDBACK_PAYABLE (refType SETTLEMENT_HOLDBACK_RECOGNIZED)</li>
 * </ul>
 *
 * <p>두 전표는 refId=settlementId 로 같지만 refType 이 달라 자연키 UNIQUE 를 각자 만족한다(멱등). H=0(유보 없음)
 * 이거나 I=0(전액 유보)인 레그는 양수 금액이 아니므로 전기를 생략한다 — 팩토리는 양수만 허용한다.
 * {@code holdbackAmount} 는 하위호환을 위해 optional 로 파싱(누락 시 0 = 전액 즉시분).
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class SettlementCreatedConsumer extends IdempotentEventConsumer {

    static final String CONSUMER_GROUP = "lemuel-account";

    private final RecordAccountEntryUseCase recordAccountEntryUseCase;

    public SettlementCreatedConsumer(RecordAccountEntryUseCase recordAccountEntryUseCase,
                                     ProcessedEventRepository processedEventRepository,
                                     ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.recordAccountEntryUseCase = recordAccountEntryUseCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.settlement-created}", groupId = CONSUMER_GROUP, containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onSettlementCreated(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() { return CONSUMER_GROUP; }

    @Override
    protected String eventType() { return "SettlementCreated"; }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        String sellerId = requiredText(node, "sellerId", eventId);
        String settlementId = requiredText(node, "settlementId", eventId);
        BigDecimal netAmount = requiredDecimal(node, "amount", eventId);
        BigDecimal holdback = optionalDecimal(node, "holdbackAmount");
        BigDecimal immediate = netAmount.subtract(holdback);

        if (immediate.signum() > 0) {
            recordAccountEntryUseCase.record(
                    AccountEntry.settlementCreatedImmediate(sellerId, settlementId, immediate));
        }
        if (holdback.signum() > 0) {
            recordAccountEntryUseCase.record(
                    AccountEntry.settlementHoldbackRecognized(sellerId, settlementId, holdback));
        }
        log.info("정산생성 2전표 적재. eventId={}, settlementId={}, immediate={}, holdback={}",
                eventId, settlementId, immediate, holdback);
    }

    /** optional 금액 — 누락/null 은 0. 숫자가 아니면 계약 위반(IAE 하위)으로 즉시 격리. */
    private static BigDecimal optionalDecimal(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value.asText());
    }
}
