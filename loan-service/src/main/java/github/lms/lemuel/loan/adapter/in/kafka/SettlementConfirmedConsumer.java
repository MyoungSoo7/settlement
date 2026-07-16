package github.lms.lemuel.loan.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.loan.application.port.in.ApplyRepaymentUseCase;
import github.lms.lemuel.loan.application.port.in.ApplyRepaymentUseCase.ApplyRepaymentCommand;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * settlement 의 정산 확정 이벤트 수신 → 상환 차감 트리거 컨슈머 (상환 saga 의 loan 측 진입점).
 *
 * <p>멱등: {@link IdempotentEventConsumer} 의 processed_events(consumer_group, event_id) +
 * loan_repayments.settlement_id UNIQUE. 여기서는 커맨드 매핑·use case 호출만 구현한다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class SettlementConfirmedConsumer extends IdempotentEventConsumer {

    private static final String CONSUMER_GROUP = "lemuel-loan";

    private final ApplyRepaymentUseCase applyRepaymentUseCase;

    public SettlementConfirmedConsumer(ApplyRepaymentUseCase applyRepaymentUseCase,
                                       ProcessedEventRepository processedEventRepository,
                                       ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.applyRepaymentUseCase = applyRepaymentUseCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.settlement-confirmed}", groupId = CONSUMER_GROUP, containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onSettlementConfirmed(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return CONSUMER_GROUP;
    }

    @Override
    protected String eventType() {
        return "SettlementConfirmed";
    }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        ApplyRepaymentCommand command = new ApplyRepaymentCommand(
                requiredLong(node, "settlementId", eventId),
                requiredLong(node, "sellerId", eventId),
                requiredDecimal(node, "amount", eventId));

        applyRepaymentUseCase.apply(command);

        log.info("상환 차감 트리거 완료. eventId={}, settlementId={}", eventId, command.settlementId());
    }
}
