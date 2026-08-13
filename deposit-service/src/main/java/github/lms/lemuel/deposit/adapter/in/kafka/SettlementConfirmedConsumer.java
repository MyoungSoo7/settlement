package github.lms.lemuel.deposit.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.deposit.application.port.in.CreditDepositUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code lemuel.settlement.confirmed} → 셀러 예치금 입금({@link CreditDepositUseCase}).
 *
 * <p>정산이 확정되면 그 금액이 셀러의 예치 계좌 available 로 들어간다. 계좌가 없으면
 * {@code credit} 이 만들어 주므로(getOrCreate) 첫 정산에도 안전하다.
 *
 * <p>★ 멱등은 세 겹이다. L1 은 발행측 outbox 의 {@code event_id} UNIQUE, L2 는 이 골격이 저장하는
 * {@code processed_events(consumer_group, event_id)}, L3 은 원장 테이블의
 * {@code UNIQUE(account_id, entry_type, reference_type, reference_id, offset_sequence)} 다.
 * 그래서 {@code referenceType} 을 상수로 고정한다 — 이 문자열이 흔들리면 L3 이 같은 정산을
 * 다른 행으로 보고 이중 입금을 막지 못한다.
 *
 * <p>account-service 도 같은 토픽을 구독하지만 컨슈머 그룹이 달라({@code lemuel-account})
 * 서로의 오프셋·멱등 원장에 영향을 주지 않는다 — 정상적인 pub/sub 팬아웃이다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class SettlementConfirmedConsumer extends IdempotentEventConsumer {

    static final String CONSUMER_GROUP = "lemuel-deposit";

    /** 원장 {@code reference_type} — L3 멱등 키의 일부라 변경 금지(과거 행과 짝이 어긋난다). */
    static final String REFERENCE_TYPE = "SETTLEMENT";

    private final CreditDepositUseCase creditDepositUseCase;

    public SettlementConfirmedConsumer(ProcessedEventRepository processedEventRepository,
                                       ObjectMapper objectMapper,
                                       CreditDepositUseCase creditDepositUseCase) {
        super(processedEventRepository, objectMapper);
        this.creditDepositUseCase = creditDepositUseCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.settlement-confirmed}", groupId = CONSUMER_GROUP,
            containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onSettlementConfirmed(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() { return CONSUMER_GROUP; }

    @Override
    protected String eventType() { return "SettlementConfirmed"; }

    @Override
    protected void handle(JsonNode payload, UUID eventId) {
        long sellerId = requiredLong(payload, "sellerId", eventId);
        BigDecimal amount = requiredDecimal(payload, "amount", eventId);
        long settlementId = requiredLong(payload, "settlementId", eventId);

        creditDepositUseCase.credit(sellerId, amount, String.valueOf(settlementId), REFERENCE_TYPE);
    }
}
