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

    @KafkaListener(topics = "${app.kafka.topic.settlement-created}", groupId = CONSUMER_GROUP)
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
        AccountEntry entry = AccountEntry.settlementCreated(
                node.get("sellerId").asText(),
                node.get("settlementId").asText(),
                new BigDecimal(node.get("amount").asText()));
        recordAccountEntryUseCase.record(entry);
        log.info("정산생성 분개 적재. eventId={}, settlementId={}", eventId, node.get("settlementId").asText());
    }
}
