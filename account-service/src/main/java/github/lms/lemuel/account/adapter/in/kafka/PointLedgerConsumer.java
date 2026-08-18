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
 * 포인트 원장 이벤트 → GL 분개.
 *
 * <p>미사용 포인트는 회사의 부채이고 보너스·적립은 판촉비다. 잔고 변화가 GL 로 넘어오지 않으면
 * 시산표가 현실과 어긋난다.
 *
 * <p>5개 토픽을 <b>한 컨슈머가</b> 받는다. 이벤트마다 클래스를 나누면 같은 골격이 다섯 벌
 * 복제되는데, 여기서는 분기가 "어느 팩토리를 부르는가" 한 줄뿐이라 나눌 이유가 없다.
 * 대신 {@code eventType()} 을 레코드에서 읽어야 하므로 헤더가 아니라 토픽으로 분기한다.
 *
 * <p>멱등은 2단이다 — 컨슈머의 {@code processed_events (lemuel-account, event_id)} 와
 * {@code account_entries (source_topic, ref_type, ref_id)} UNIQUE.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class PointLedgerConsumer extends IdempotentEventConsumer {

    static final String CONSUMER_GROUP = "lemuel-account";

    private final RecordAccountEntryUseCase recordAccountEntryUseCase;

    /** 현재 처리 중인 레코드의 토픽 — {@code handle} 이 어느 분개를 만들지 결정한다. */
    private final ThreadLocal<String> currentTopic = new ThreadLocal<>();

    public PointLedgerConsumer(RecordAccountEntryUseCase recordAccountEntryUseCase,
                               ProcessedEventRepository processedEventRepository,
                               ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.recordAccountEntryUseCase = recordAccountEntryUseCase;
    }

    @KafkaListener(topics = {
            "${app.kafka.topic.point-charged}",
            "${app.kafka.topic.point-granted}",
            "${app.kafka.topic.point-used}",
            "${app.kafka.topic.point-restored}",
            "${app.kafka.topic.point-expired}",
    }, groupId = CONSUMER_GROUP, containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onPointEvent(ConsumerRecord<String, String> record, Acknowledgment ack) {
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
        return "Point";
    }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        String topic = currentTopic.get();
        String userId = requiredText(node, "userId", eventId);
        BigDecimal amount = requiredDecimal(node, "amount", eventId);

        AccountEntry entry = switch (suffixOf(topic)) {
            case "charged" -> AccountEntry.pointCharged(userId, requiredText(node, "lotId", eventId), amount);
            case "granted" -> AccountEntry.pointGranted(userId, requiredText(node, "lotId", eventId), amount);
            case "used" -> AccountEntry.pointUsed(userId, requiredText(node, "entryId", eventId), amount);
            case "restored" -> AccountEntry.pointRestored(userId, requiredText(node, "entryId", eventId), amount);
            case "expired" -> AccountEntry.pointExpired(userId, requiredText(node, "lotId", eventId), amount);
            // 카탈로그에 없는 토픽이 이 리스너에 닿았다는 뜻 — 조용히 넘기면 분개가 통째로 누락된다.
            default -> throw new IllegalStateException(
                    "알 수 없는 포인트 토픽입니다: " + topic + ", eventId=" + eventId);
        };

        recordAccountEntryUseCase.record(entry);
        log.info("포인트 분개 적재. eventId={}, topic={}, userId={}, amount={}",
                eventId, topic, userId, amount);
    }

    /** {@code lemuel.point.used} → {@code used}. */
    private static String suffixOf(String topic) {
        if (topic == null) {
            return "";
        }
        int lastDot = topic.lastIndexOf('.');
        return lastDot < 0 ? topic : topic.substring(lastDot + 1);
    }
}
