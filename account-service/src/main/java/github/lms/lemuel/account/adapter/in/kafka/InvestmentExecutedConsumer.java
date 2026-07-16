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
 * investment.executed → DR INVESTMENT_ASSET / CR CASH 분개 적재(투자 집행).
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class InvestmentExecutedConsumer extends IdempotentEventConsumer {

    static final String CONSUMER_GROUP = "lemuel-account";

    private final RecordAccountEntryUseCase recordAccountEntryUseCase;

    public InvestmentExecutedConsumer(RecordAccountEntryUseCase recordAccountEntryUseCase,
                                      ProcessedEventRepository processedEventRepository,
                                      ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.recordAccountEntryUseCase = recordAccountEntryUseCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.investment-executed}", groupId = CONSUMER_GROUP, containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onInvestmentExecuted(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() { return CONSUMER_GROUP; }

    @Override
    protected String eventType() { return "InvestmentExecuted"; }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        String orderId = requiredText(node, "orderId", eventId);
        AccountEntry entry = AccountEntry.investmentExecuted(
                requiredText(node, "sellerId", eventId),
                orderId,
                requiredDecimal(node, "amount", eventId));
        recordAccountEntryUseCase.record(entry);
        log.info("투자 집행 분개 적재. eventId={}, orderId={}", eventId, orderId);
    }
}
