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

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 기프트카드 원장 이벤트 → GL 분개.
 *
 * <p>미사용 상품권 잔액은 회사의 부채이고 무상 발행분은 판촉비다. 포인트와 <b>같은 구조지만 계정이
 * 다르다</b> — 두 부채를 한 계정에 담으면 시산표에서 분리할 수 없다.
 *
 * <p>{@code PointLedgerConsumer} 와 같은 이유로 4개 토픽을 한 컨슈머가 받는다: 분기가 "어느 팩토리를
 * 부르는가" 한 줄뿐이라 클래스를 넷으로 복제할 이유가 없다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class GiftCardLedgerConsumer extends IdempotentEventConsumer {

    static final String CONSUMER_GROUP = "lemuel-account";

    private final RecordAccountEntryUseCase recordAccountEntryUseCase;

    /** 현재 처리 중인 레코드의 토픽 — {@code handle} 이 어느 분개를 만들지 결정한다. */
    private final ThreadLocal<String> currentTopic = new ThreadLocal<>();

    public GiftCardLedgerConsumer(RecordAccountEntryUseCase recordAccountEntryUseCase,
                                  ProcessedEventRepository processedEventRepository,
                                  ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.recordAccountEntryUseCase = recordAccountEntryUseCase;
    }

    @KafkaListener(topics = {
            "${app.kafka.topic.giftcard-registered}",
            "${app.kafka.topic.giftcard-used}",
            "${app.kafka.topic.giftcard-restored}",
            "${app.kafka.topic.giftcard-expired}",
    }, groupId = CONSUMER_GROUP, containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onGiftCardEvent(ConsumerRecord<String, String> record, Acknowledgment ack) {
        currentTopic.set(record.topic());
        try {
            consume(record, ack);
        } finally {
            currentTopic.remove();
        }
    }

    @Override
    protected String consumerGroup() {
        return CONSUMER_GROUP;
    }

    @Override
    protected String eventType() {
        return "GiftCard";
    }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        String topic = currentTopic.get();
        String userId = requiredText(node, "userId", eventId);
        BigDecimal amount = requiredDecimal(node, "amount", eventId);

        AccountEntry entry = switch (suffixOf(topic)) {
            case "registered" -> AccountEntry.giftCardRegistered(
                    userId, requiredText(node, "giftCardId", eventId), amount);
            case "used" -> AccountEntry.giftCardUsed(
                    userId, requiredText(node, "entryId", eventId), amount);
            case "restored" -> AccountEntry.giftCardRestored(
                    userId, requiredText(node, "entryId", eventId), amount);
            case "expired" -> AccountEntry.giftCardExpired(
                    userId, requiredText(node, "giftCardId", eventId), amount);
            // 카탈로그에 없는 토픽이 이 리스너에 닿았다는 뜻 — 조용히 넘기면 분개가 통째로 누락된다.
            default -> throw new IllegalStateException(
                    "알 수 없는 기프트카드 토픽입니다: " + topic + ", eventId=" + eventId);
        };

        recordAccountEntryUseCase.record(entry);
        log.info("기프트카드 분개 적재. eventId={}, topic={}, userId={}, amount={}",
                eventId, topic, userId, amount);
    }

    /** {@code lemuel.giftcard.used} → {@code used}. */
    private static String suffixOf(String topic) {
        if (topic == null) {
            return "";
        }
        int lastDot = topic.lastIndexOf('.');
        return lastDot < 0 ? topic : topic.substring(lastDot + 1);
    }
}
