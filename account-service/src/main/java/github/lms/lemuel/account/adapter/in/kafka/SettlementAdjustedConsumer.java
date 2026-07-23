package github.lms.lemuel.account.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.account.application.port.in.RecordAccountEntryUseCase;
import github.lms.lemuel.account.domain.AccountEntry;
import github.lms.lemuel.account.domain.GlAccount;
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
 * settlement.adjusted → DR {targetLeg} / CR CASH 분개 적재(ADR 0026 Option ①).
 *
 * <p>확정 전 조정(환불/클로백 감액)이 즉시분(SELLER_PAYABLE)인지 유보분(HOLDBACK_PAYABLE)인지 payload
 * {@code targetLeg} 로 분기해 해당 부채를 감액하고 현금을 조정한다. targetLeg 가 두 계정 외 값이면 팩토리가
 * 거부(non-retryable → DLT). refId=adjustmentId.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class SettlementAdjustedConsumer extends IdempotentEventConsumer {

    static final String CONSUMER_GROUP = "lemuel-account";

    private final RecordAccountEntryUseCase recordAccountEntryUseCase;

    public SettlementAdjustedConsumer(RecordAccountEntryUseCase recordAccountEntryUseCase,
                                      ProcessedEventRepository processedEventRepository,
                                      ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.recordAccountEntryUseCase = recordAccountEntryUseCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.settlement-adjusted}", groupId = CONSUMER_GROUP, containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onSettlementAdjusted(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() { return CONSUMER_GROUP; }

    @Override
    protected String eventType() { return "SettlementAdjusted"; }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        String adjustmentId = requiredText(node, "adjustmentId", eventId);
        GlAccount targetLeg = parseTargetLeg(requiredText(node, "targetLeg", eventId), eventId);
        recordAccountEntryUseCase.record(AccountEntry.settlementAdjusted(
                requiredText(node, "sellerId", eventId),
                adjustmentId,
                requiredDecimal(node, "amount", eventId),
                targetLeg));
        log.info("확정전조정 분개 적재. eventId={}, adjustmentId={}, targetLeg={}", eventId, adjustmentId, targetLeg);
    }

    /** targetLeg 문자열 → GlAccount. 알 수 없는 값은 계약 위반(non-retryable)으로 즉시 격리. */
    private static GlAccount parseTargetLeg(String raw, UUID eventId) {
        try {
            return GlAccount.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("알 수 없는 targetLeg: " + raw + ", eventId=" + eventId, ex);
        }
    }
}
