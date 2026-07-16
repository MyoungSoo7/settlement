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

import java.util.UUID;

/**
 * settlement.confirmed → DR SELLER_PAYABLE / CR SETTLEMENT_SCHEDULED 분개 적재(예정 상계).
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class SettlementConfirmedConsumer extends IdempotentEventConsumer {

    static final String CONSUMER_GROUP = "lemuel-account";

    private final RecordAccountEntryUseCase recordAccountEntryUseCase;

    public SettlementConfirmedConsumer(RecordAccountEntryUseCase recordAccountEntryUseCase,
                                       ProcessedEventRepository processedEventRepository,
                                       ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.recordAccountEntryUseCase = recordAccountEntryUseCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.settlement-confirmed}", groupId = CONSUMER_GROUP, containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onSettlementConfirmed(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() { return CONSUMER_GROUP; }

    @Override
    protected String eventType() { return "SettlementConfirmed"; }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        String settlementId = requiredText(node, "settlementId", eventId);
        AccountEntry entry = AccountEntry.settlementConfirmed(
                requiredText(node, "sellerId", eventId),
                settlementId,
                requiredDecimal(node, "amount", eventId));
        recordAccountEntryUseCase.record(entry);
        log.info("정산확정 분개 적재. eventId={}, settlementId={}", eventId, settlementId);
    }
}
