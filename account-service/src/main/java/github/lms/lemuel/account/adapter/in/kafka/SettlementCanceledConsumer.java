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
 * settlement.canceled → 즉시분·유보분 잔여 소멸 <b>2전표</b> 적재(ADR 0026 Option ①, compound 금지).
 *
 * <ul>
 *   <li>즉시 잔여 I → DR SELLER_PAYABLE / CR CASH (refType SETTLEMENT_CANCELED_PAYABLE)</li>
 *   <li>유보 잔여 H → DR HOLDBACK_PAYABLE / CR CASH (refType SETTLEMENT_CANCELED_HOLDBACK)</li>
 * </ul>
 *
 * <p>두 전표는 refId=settlementId 로 같지만 refType 이 달라 각자 자연키 UNIQUE 를 만족한다(멱등). 잔여가 0 인
 * 레그는 양수 금액이 아니므로 전기를 생략한다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class SettlementCanceledConsumer extends IdempotentEventConsumer {

    static final String CONSUMER_GROUP = "lemuel-account";

    private final RecordAccountEntryUseCase recordAccountEntryUseCase;

    public SettlementCanceledConsumer(RecordAccountEntryUseCase recordAccountEntryUseCase,
                                      ProcessedEventRepository processedEventRepository,
                                      ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.recordAccountEntryUseCase = recordAccountEntryUseCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.settlement-canceled}", groupId = CONSUMER_GROUP, containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onSettlementCanceled(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() { return CONSUMER_GROUP; }

    @Override
    protected String eventType() { return "SettlementCanceled"; }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        String sellerId = requiredText(node, "sellerId", eventId);
        String settlementId = requiredText(node, "settlementId", eventId);
        BigDecimal immediate = requiredDecimal(node, "immediateAmount", eventId);
        BigDecimal holdback = requiredDecimal(node, "holdbackAmount", eventId);

        if (immediate.signum() > 0) {
            recordAccountEntryUseCase.record(
                    AccountEntry.settlementCanceledPayable(sellerId, settlementId, immediate));
        }
        if (holdback.signum() > 0) {
            recordAccountEntryUseCase.record(
                    AccountEntry.settlementCanceledHoldback(sellerId, settlementId, holdback));
        }
        log.info("정산취소 잔여소멸 전기. eventId={}, settlementId={}, immediate={}, holdback={}",
                eventId, settlementId, immediate, holdback);
    }
}
