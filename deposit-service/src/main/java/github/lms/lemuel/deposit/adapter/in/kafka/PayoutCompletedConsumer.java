package github.lms.lemuel.deposit.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.deposit.application.port.in.DebitDepositUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code lemuel.payout.completed} → 셀러 예치금 출금({@link DebitDepositUseCase}).
 *
 * <p>지급이 완료되면 그만큼 예치 계좌 available 에서 빠진다.
 *
 * <p>★ 잔고 부족·계좌 부재를 삼키지 않는 이유:
 * {@code debit} 은 available 이 모자라면 {@code InsufficientDepositException} 을, 계좌가 없으면
 * {@code IllegalStateException} 을 던진다. 둘 다 여기서 catch 하지 않고 전파시킨다 — 재시도 후 DLT 로
 * 남아 운영자가 보게 된다. "지급은 나갔는데 차감할 예치금이 없다"는 것은 정상 결과가 아니라 원장이
 * 어긋났다는 신호이고, 조용히 ack 하면 그 불일치가 영구히 사라진다.
 * (상계 부족분을 shortfall 레코드로 정상 처리하는 {@code applyOffset} 과 대비되는 지점이다 —
 * 그쪽은 부족이 설계된 결과지만, 이쪽은 아니다.)
 *
 * <p>멱등 3계층과 {@code referenceType} 고정 이유는 {@link SettlementConfirmedConsumer} 참조.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class PayoutCompletedConsumer extends IdempotentEventConsumer {

    static final String CONSUMER_GROUP = "lemuel-deposit";

    /** 원장 {@code reference_type} — L3 멱등 키의 일부라 변경 금지. */
    static final String REFERENCE_TYPE = "PAYOUT";

    private final DebitDepositUseCase debitDepositUseCase;

    public PayoutCompletedConsumer(ProcessedEventRepository processedEventRepository,
                                   ObjectMapper objectMapper,
                                   DebitDepositUseCase debitDepositUseCase) {
        super(processedEventRepository, objectMapper);
        this.debitDepositUseCase = debitDepositUseCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.payout-completed}", groupId = CONSUMER_GROUP,
            containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onPayoutCompleted(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() { return CONSUMER_GROUP; }

    @Override
    protected String eventType() { return "PayoutCompleted"; }

    @Override
    protected void handle(JsonNode payload, UUID eventId) {
        long sellerId = requiredLong(payload, "sellerId", eventId);
        BigDecimal amount = requiredDecimal(payload, "amount", eventId);
        long payoutId = requiredLong(payload, "payoutId", eventId);

        debitDepositUseCase.debit(sellerId, amount, String.valueOf(payoutId), REFERENCE_TYPE);
    }
}
