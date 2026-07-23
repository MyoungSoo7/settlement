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
 * seller_recovery.opened → DR SELLER_RECOVERY_RECEIVABLE / CR CASH 분개 적재(ADR 0026 Option ①, P0-6 GL mirror).
 *
 * <p>지급이 이미 나간 뒤 감액이 확정되면 셀러로부터 회수할 채권 R 을 인식한다(현금 유출 대응). refId=recoveryId.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class SellerRecoveryOpenedConsumer extends IdempotentEventConsumer {

    static final String CONSUMER_GROUP = "lemuel-account";

    private final RecordAccountEntryUseCase recordAccountEntryUseCase;

    public SellerRecoveryOpenedConsumer(RecordAccountEntryUseCase recordAccountEntryUseCase,
                                        ProcessedEventRepository processedEventRepository,
                                        ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.recordAccountEntryUseCase = recordAccountEntryUseCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.seller-recovery-opened}", groupId = CONSUMER_GROUP, containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onRecoveryOpened(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() { return CONSUMER_GROUP; }

    @Override
    protected String eventType() { return "SellerRecoveryOpened"; }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        String recoveryId = requiredText(node, "recoveryId", eventId);
        recordAccountEntryUseCase.record(AccountEntry.recoveryOpened(
                requiredText(node, "sellerId", eventId),
                recoveryId,
                requiredDecimal(node, "amount", eventId)));
        log.info("회수채권 발생 분개 적재. eventId={}, recoveryId={}", eventId, recoveryId);
    }
}
