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
 * settlement.created → DR SETTLEMENT_SCHEDULED / CR SELLER_PAYABLE 분개 적재.
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
        String settlementId = requiredText(node, "settlementId", eventId);
        AccountEntry entry = AccountEntry.settlementCreated(
                requiredText(node, "sellerId", eventId),
                settlementId,
                requiredDecimal(node, "amount", eventId));
        recordAccountEntryUseCase.record(entry);
        log.info("정산생성 분개 적재. eventId={}, settlementId={}", eventId, settlementId);
    }
}
