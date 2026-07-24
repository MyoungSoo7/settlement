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
 * settlement.holdback_consumed → DR HOLDBACK_PAYABLE / CR CASH 분개 적재(ADR 0026 Option ①).
 *
 * <p>환불/클로백이 유보(홀드백)에서 흡수될 때 유보 부채를 감액하고 현금을 정산한다. 자연키
 * refId=sourceAdjustmentId(감액을 유발한 조정 id) — 조정당 1회 멱등.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class SettlementHoldbackConsumedConsumer extends IdempotentEventConsumer {

    static final String CONSUMER_GROUP = "lemuel-account";

    private final RecordAccountEntryUseCase recordAccountEntryUseCase;

    public SettlementHoldbackConsumedConsumer(RecordAccountEntryUseCase recordAccountEntryUseCase,
                                              ProcessedEventRepository processedEventRepository,
                                              ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.recordAccountEntryUseCase = recordAccountEntryUseCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.settlement-holdback-consumed}", groupId = CONSUMER_GROUP, containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onHoldbackConsumed(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() { return CONSUMER_GROUP; }

    @Override
    protected String eventType() { return "SettlementHoldbackConsumed"; }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        String sourceAdjustmentId = requiredText(node, "sourceAdjustmentId", eventId);
        recordAccountEntryUseCase.record(AccountEntry.holdbackConsumed(
                requiredText(node, "sellerId", eventId),
                sourceAdjustmentId,
                requiredDecimal(node, "amount", eventId)));
        log.info("유보소진 분개 적재. eventId={}, sourceAdjustmentId={}", eventId, sourceAdjustmentId);
    }
}
