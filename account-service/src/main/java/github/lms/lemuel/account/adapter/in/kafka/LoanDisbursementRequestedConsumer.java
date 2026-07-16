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
 * loan.disbursement_requested → DR LOAN_RECEIVABLE / CR CASH 분개 적재(셀러 선정산 선지급).
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class LoanDisbursementRequestedConsumer extends IdempotentEventConsumer {

    static final String CONSUMER_GROUP = "lemuel-account";

    private final RecordAccountEntryUseCase recordAccountEntryUseCase;

    public LoanDisbursementRequestedConsumer(RecordAccountEntryUseCase recordAccountEntryUseCase,
                                             ProcessedEventRepository processedEventRepository,
                                             ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.recordAccountEntryUseCase = recordAccountEntryUseCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.loan-disbursement-requested}", groupId = CONSUMER_GROUP, containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onLoanDisbursed(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() { return CONSUMER_GROUP; }

    @Override
    protected String eventType() { return "LoanDisbursementRequested"; }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        AccountEntry entry = AccountEntry.loanDisbursed(
                node.get("sellerId").asText(),
                node.get("loanId").asText(),
                new BigDecimal(node.get("amount").asText()));
        recordAccountEntryUseCase.record(entry);
        log.info("선정산 선지급 분개 적재. eventId={}, loanId={}", eventId, node.get("loanId").asText());
    }
}
