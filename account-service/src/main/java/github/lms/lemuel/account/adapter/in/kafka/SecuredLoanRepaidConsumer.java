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
 * loan.secured_loan_repaid → DR CASH / CR SECURED_LOAN_RECEIVABLE 분개 적재(담보/개인신용 대출 완제).
 *
 * <p>이벤트는 완제 시점에만 발행되고 principal 은 계약 원금이다 — 실행 분개와 동액이라 회차·중도상환
 * 경로와 무관하게 차주의 SECURED_LOAN_RECEIVABLE 잔액이 0 으로 닫힌다. interestPaid·prepaymentFee 는
 * loan 자체 원장에서 수익 인식이 끝난 값이라 계정계에서는 분개하지 않는다(원금만).
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class SecuredLoanRepaidConsumer extends IdempotentEventConsumer {

    static final String CONSUMER_GROUP = "lemuel-account";

    private final RecordAccountEntryUseCase recordAccountEntryUseCase;

    public SecuredLoanRepaidConsumer(RecordAccountEntryUseCase recordAccountEntryUseCase,
                                     ProcessedEventRepository processedEventRepository,
                                     ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.recordAccountEntryUseCase = recordAccountEntryUseCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.secured-loan-repaid}", groupId = CONSUMER_GROUP, containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onSecuredLoanRepaid(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() { return CONSUMER_GROUP; }

    @Override
    protected String eventType() { return "SecuredLoanRepaid"; }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        String borrowerUserId = requiredText(node, "borrowerUserId", eventId);
        String loanId = requiredText(node, "loanId", eventId);
        AccountEntry entry = AccountEntry.securedLoanRepaid(
                borrowerUserId,
                loanId,
                requiredDecimal(node, "principal", eventId));
        recordAccountEntryUseCase.record(entry);
        log.info("담보대출 완제 분개 적재. eventId={}, loanId={}, borrowerUserId={}", eventId, loanId, borrowerUserId);
    }
}
