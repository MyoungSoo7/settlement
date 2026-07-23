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
 * seller_recovery.offset → DR SELLER_PAYABLE / CR SELLER_RECOVERY_RECEIVABLE 분개 적재(ADR 0026 Option ①).
 *
 * <p>신규 정산의 즉시 미지급금으로 지급후 회수채권을 상계한다(회수채권 감소). 자연키 refId=allocationId(상계
 * 할당 id) — 상계 건별 1회 멱등.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class SellerRecoveryOffsetConsumer extends IdempotentEventConsumer {

    static final String CONSUMER_GROUP = "lemuel-account";

    private final RecordAccountEntryUseCase recordAccountEntryUseCase;

    public SellerRecoveryOffsetConsumer(RecordAccountEntryUseCase recordAccountEntryUseCase,
                                        ProcessedEventRepository processedEventRepository,
                                        ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.recordAccountEntryUseCase = recordAccountEntryUseCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.seller-recovery-offset}", groupId = CONSUMER_GROUP, containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onRecoveryOffset(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() { return CONSUMER_GROUP; }

    @Override
    protected String eventType() { return "SellerRecoveryOffset"; }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        String allocationId = requiredText(node, "allocationId", eventId);
        recordAccountEntryUseCase.record(AccountEntry.recoveryOffset(
                requiredText(node, "sellerId", eventId),
                allocationId,
                requiredDecimal(node, "amount", eventId)));
        log.info("회수 상계 분개 적재. eventId={}, allocationId={}", eventId, allocationId);
    }
}
