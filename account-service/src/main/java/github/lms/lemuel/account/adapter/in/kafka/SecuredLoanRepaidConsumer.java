package github.lms.lemuel.account.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.account.application.port.in.RecordAccountEntryUseCase;
import github.lms.lemuel.account.application.port.out.LoadAccountEntryPort;
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
 * loan.secured_loan_repaid → 완제 <b>신호</b>만 소비한다. GL 분개는 만들지 않는다(#183).
 *
 * <p>예전에는 이 이벤트 하나로 계약 원금 전액을 대변 처리했다. 그런데 이 이벤트는 완제 시점에만
 * 발행되므로, 회차 상환·중도상환으로 loan 쪽 채권이 줄어드는 동안 계정계는 최초 원금을 그대로
 * 들고 있었다 — 완제 전까지 시산표와 실체화 잔액이 계속 틀린 값이었다.
 *
 * <p>이제 원금 감소는 {@link SecuredLoanPrincipalRepaidConsumer} 가 건별로 전기하고, 마지막
 * 상환분까지 포함되므로 완제 시점에 잔액이 자연히 0 으로 닫힌다. 여기서 또 전기하면 이중 계상이다.
 *
 * <p><b>롤아웃 호환(코드리뷰 지적)</b>: loan/account 를 독립 배포하면 어느 쪽이 먼저 뜨느냐에 따라
 * 채권이 어긋난다 — account 가 먼저 뜨면 구 loan 은 건별 이벤트를 안 보내므로 완제를 무시한 채
 * 채권이 남고, loan 이 먼저 뜨면 구 account 가 완제로 원금 전액을 이미 대변 처리한 뒤 신 account 가
 * 보관된 건별 이벤트를 earliest 부터 다시 소비해 이중 계상된다.
 * 그래서 <b>해당 대출에 건별 전표가 하나라도 있으면 분개하지 않고, 하나도 없으면 구 방식대로
 * 계약 원금을 전기</b>한다. 두 배포 순서 모두에서, 그리고 이 변경 이전부터 진행 중이던 대출에서도
 * 채권이 정확히 한 번만 닫힌다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class SecuredLoanRepaidConsumer extends IdempotentEventConsumer {

    static final String CONSUMER_GROUP = "lemuel-account";

    private final RecordAccountEntryUseCase recordAccountEntryUseCase;
    private final LoadAccountEntryPort loadAccountEntryPort;

    public SecuredLoanRepaidConsumer(RecordAccountEntryUseCase recordAccountEntryUseCase,
                                     LoadAccountEntryPort loadAccountEntryPort,
                                     ProcessedEventRepository processedEventRepository,
                                     ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.recordAccountEntryUseCase = recordAccountEntryUseCase;
        this.loadAccountEntryPort = loadAccountEntryPort;
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

        if (loadAccountEntryPort.hasPrincipalRepaidEntry(loanId)) {
            log.info("담보대출 완제 확인(분개 없음 — 원금은 건별 전기됨). eventId={}, loanId={}, borrowerUserId={}",
                    eventId, loanId, borrowerUserId);
            return;
        }
        // 건별 전표가 하나도 없다 = 구 loan-service 가 발행한 완제다. 구 방식대로 계약 원금을 닫는다.
        recordAccountEntryUseCase.record(AccountEntry.securedLoanRepaid(
                borrowerUserId, loanId, requiredDecimal(node, "principal", eventId)));
        log.info("담보대출 완제 분개 적재(건별 전표 없음 — 구 발행자 호환 경로). eventId={}, loanId={}",
                eventId, loanId);
    }
}
