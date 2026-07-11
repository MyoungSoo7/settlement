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
 * loan.repayment_applied → DR CASH / CR LOAN_RECEIVABLE 분개 적재(대출 상환 차감).
 *
 * <p>deducted 가 0 이면 실제 차감이 없었던 정산이므로 분개를 생략한다(팩토리는 양수만 허용).
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class LoanRepaymentAppliedConsumer extends IdempotentEventConsumer {

    static final String CONSUMER_GROUP = "lemuel-account";

    private final RecordAccountEntryUseCase recordAccountEntryUseCase;

    public LoanRepaymentAppliedConsumer(RecordAccountEntryUseCase recordAccountEntryUseCase,
                                        ProcessedEventRepository processedEventRepository,
                                        ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.recordAccountEntryUseCase = recordAccountEntryUseCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.loan-repayment-applied}", groupId = CONSUMER_GROUP)
    @Transactional
    public void onLoanRepaid(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() { return CONSUMER_GROUP; }

    @Override
    protected String eventType() { return "LoanRepaymentApplied"; }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        BigDecimal deducted = new BigDecimal(node.get("deducted").asText());
        if (deducted.signum() <= 0) {
            log.info("차감액 0 — 상환 분개 생략. eventId={}, settlementId={}", eventId, node.get("settlementId").asText());
            return;
        }
        AccountEntry entry = AccountEntry.loanRepaid(
                node.get("sellerId").asText(),
                node.get("settlementId").asText(),
                deducted);
        recordAccountEntryUseCase.record(entry);
        log.info("대출 상환 분개 적재. eventId={}, settlementId={}", eventId, node.get("settlementId").asText());
    }
}
