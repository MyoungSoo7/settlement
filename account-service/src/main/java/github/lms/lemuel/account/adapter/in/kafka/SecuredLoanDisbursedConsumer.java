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
 * loan.secured_loan_disbursed → DR SECURED_LOAN_RECEIVABLE / CR CASH 분개 적재(담보/개인신용 대출, 원금만).
 *
 * <p>owner 는 BORROWER(ownerId = borrowerUserId — 개인·법인 공통 차주 식별자). 이자·수수료는 loan
 * 자체 원장 소관이라 이 분개에서 인식하지 않는다(법인 대출 선례와 동형). annualRatePercent·termMonths 는
 * GL 무관 필드라 소비하지 않는다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class SecuredLoanDisbursedConsumer extends IdempotentEventConsumer {

    static final String CONSUMER_GROUP = "lemuel-account";

    private final RecordAccountEntryUseCase recordAccountEntryUseCase;

    public SecuredLoanDisbursedConsumer(RecordAccountEntryUseCase recordAccountEntryUseCase,
                                        ProcessedEventRepository processedEventRepository,
                                        ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.recordAccountEntryUseCase = recordAccountEntryUseCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.secured-loan-disbursed}", groupId = CONSUMER_GROUP, containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onSecuredLoanDisbursed(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() { return CONSUMER_GROUP; }

    @Override
    protected String eventType() { return "SecuredLoanDisbursed"; }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        String borrowerUserId = requiredText(node, "borrowerUserId", eventId);
        String loanId = requiredText(node, "loanId", eventId);
        AccountEntry entry = AccountEntry.securedLoanDisbursed(
                borrowerUserId,
                loanId,
                requiredDecimal(node, "principal", eventId));
        recordAccountEntryUseCase.record(entry);
        log.info("담보대출 실행 분개 적재. eventId={}, loanId={}, borrowerUserId={}", eventId, loanId, borrowerUserId);
    }
}
