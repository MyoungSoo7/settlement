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
 * loan.corporate_loan_disbursed → DR CORPORATE_LOAN_RECEIVABLE / CR CASH 분개 적재(법인 대출, 원금만).
 *
 * <p>owner 는 CORPORATE(ownerId = stockCode). fee 는 이 분개에서 인식하지 않는다(원금만 집계).
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class CorporateLoanDisbursedConsumer extends IdempotentEventConsumer {

    static final String CONSUMER_GROUP = "lemuel-account";

    private final RecordAccountEntryUseCase recordAccountEntryUseCase;

    public CorporateLoanDisbursedConsumer(RecordAccountEntryUseCase recordAccountEntryUseCase,
                                          ProcessedEventRepository processedEventRepository,
                                          ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.recordAccountEntryUseCase = recordAccountEntryUseCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.corporate-loan-disbursed}", groupId = CONSUMER_GROUP, containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onCorporateLoanDisbursed(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() { return CONSUMER_GROUP; }

    @Override
    protected String eventType() { return "CorporateLoanDisbursed"; }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        AccountEntry entry = AccountEntry.corporateLoanDisbursed(
                node.get("stockCode").asText(),
                node.get("loanId").asText(),
                new BigDecimal(node.get("principal").asText()));
        recordAccountEntryUseCase.record(entry);
        log.info("법인 대출 분개 적재. eventId={}, loanId={}, stockCode={}",
                eventId, node.get("loanId").asText(), node.get("stockCode").asText());
    }
}
