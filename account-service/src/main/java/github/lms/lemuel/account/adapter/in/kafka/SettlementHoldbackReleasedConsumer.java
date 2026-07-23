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
 * settlement.holdback_released → DR HOLDBACK_PAYABLE / CR SELLER_PAYABLE 분개 적재(ADR 0026 Option ①).
 *
 * <p>유보 해제 시 잔여 홀드백을 즉시지급 대상(SELLER_PAYABLE)으로 재분류한다. 이 재분류 선행 덕에 후속
 * 지급은 즉시분과 동일하게 payout.completed(DR SELLER_PAYABLE/CR CASH)로 전기된다. refId=settlementId.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class SettlementHoldbackReleasedConsumer extends IdempotentEventConsumer {

    static final String CONSUMER_GROUP = "lemuel-account";

    private final RecordAccountEntryUseCase recordAccountEntryUseCase;

    public SettlementHoldbackReleasedConsumer(RecordAccountEntryUseCase recordAccountEntryUseCase,
                                              ProcessedEventRepository processedEventRepository,
                                              ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.recordAccountEntryUseCase = recordAccountEntryUseCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.settlement-holdback-released}", groupId = CONSUMER_GROUP, containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onHoldbackReleased(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() { return CONSUMER_GROUP; }

    @Override
    protected String eventType() { return "SettlementHoldbackReleased"; }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        String settlementId = requiredText(node, "settlementId", eventId);
        recordAccountEntryUseCase.record(AccountEntry.holdbackReleased(
                requiredText(node, "sellerId", eventId),
                settlementId,
                requiredDecimal(node, "amount", eventId)));
        log.info("유보해제 분개 적재. eventId={}, settlementId={}", eventId, settlementId);
    }
}
