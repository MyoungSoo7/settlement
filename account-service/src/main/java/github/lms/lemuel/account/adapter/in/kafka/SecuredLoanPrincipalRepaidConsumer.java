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
 * loan.secured_loan_principal_repaid → DR CASH / CR SECURED_LOAN_RECEIVABLE 건별 분개(#183).
 *
 * <p>전에는 완제 이벤트 하나로 계약 원금 전액을 대변 처리했다. 그래서 회차 상환·중도상환으로
 * loan 쪽 채권이 줄어드는 동안 계정계는 최초 원금을 그대로 들고 있었고, 완제 전까지 시산표와
 * 실체화 잔액이 계속 틀린 값이었다. 이제 원금이 실제로 줄어들 때마다 그 금액으로 전기한다.
 *
 * <p>이자·중도상환수수료는 여기서 분개하지 않는다 — loan 자체 원장에서 수익 인식이 끝난 값이다(원금만).
 *
 * <p>담보 처분·대위변제로 회수되지 못한 <b>대손 상각분</b>은 아직 전기하지 않는다. 손실 계정이
 * 계정과목에 없어 임의로 만들면 안 되기 때문이다 — 그 경로의 채권은 GL 에서 0 으로 닫히지 않는다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class SecuredLoanPrincipalRepaidConsumer extends IdempotentEventConsumer {

    static final String CONSUMER_GROUP = "lemuel-account";

    private final RecordAccountEntryUseCase recordAccountEntryUseCase;

    public SecuredLoanPrincipalRepaidConsumer(RecordAccountEntryUseCase recordAccountEntryUseCase,
                                              ProcessedEventRepository processedEventRepository,
                                              ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.recordAccountEntryUseCase = recordAccountEntryUseCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.secured-loan-principal-repaid}", groupId = CONSUMER_GROUP,
            containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onSecuredLoanPrincipalRepaid(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() { return CONSUMER_GROUP; }

    @Override
    protected String eventType() { return "SecuredLoanPrincipalRepaid"; }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        String borrowerUserId = requiredText(node, "borrowerUserId", eventId);
        String loanId = requiredText(node, "loanId", eventId);
        AccountEntry entry = AccountEntry.securedLoanPrincipalRepaid(
                borrowerUserId,
                loanId,
                eventId.toString(),
                requiredDecimal(node, "principalRepaid", eventId));
        recordAccountEntryUseCase.record(entry);
        log.info("담보대출 원금상환 분개 적재. eventId={}, loanId={}, borrowerUserId={}", eventId, loanId, borrowerUserId);
    }
}
