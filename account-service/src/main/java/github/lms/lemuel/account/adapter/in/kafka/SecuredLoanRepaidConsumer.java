package github.lms.lemuel.account.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * <p>리스너를 지우지 않고 남기는 이유: 이벤트 계약·스키마 검증과 processed_events 멱등 추적을
 * 그대로 유지하고, 완제 사실을 감사 로그로 남기기 위해서다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class SecuredLoanRepaidConsumer extends IdempotentEventConsumer {

    static final String CONSUMER_GROUP = "lemuel-account";

    public SecuredLoanRepaidConsumer(ProcessedEventRepository processedEventRepository,
                                     ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
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
        // 필수 필드는 계속 검증한다 — 계약이 깨지면 여기서 드러나야 한다.
        String borrowerUserId = requiredText(node, "borrowerUserId", eventId);
        String loanId = requiredText(node, "loanId", eventId);
        log.info("담보대출 완제 확인(분개 없음 — 원금은 건별 전기). eventId={}, loanId={}, borrowerUserId={}",
                eventId, loanId, borrowerUserId);
    }
}
