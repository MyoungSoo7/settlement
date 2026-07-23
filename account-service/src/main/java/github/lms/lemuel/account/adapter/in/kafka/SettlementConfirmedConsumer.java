package github.lms.lemuel.account.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * settlement.confirmed → <b>GL 무전표</b>(ADR 0026 Option A — 상태 전이·멱등 마커만).
 *
 * <p>Option A 에서 SELLER_PAYABLE 상계는 확정이 아니라 지급(payout.completed) 시점에 일어난다. 따라서 이
 * 컨슈머는 분개를 적재하지 않는다(record 미호출). 다만 토픽·컨슈머는 유지한다 — 계약(ADR 0024) 및 멱등
 * 원장({@code processed_events}) 정합성을 위해 이벤트를 정상 소비(마커 저장 + ack)해야 한다. GL 전기가
 * 없으므로 페이로드 필드 파싱도 강제하지 않는다(필드 누락으로 정상 확정 이벤트를 DLT 로 보내지 않는다).
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class SettlementConfirmedConsumer extends IdempotentEventConsumer {

    static final String CONSUMER_GROUP = "lemuel-account";

    public SettlementConfirmedConsumer(ProcessedEventRepository processedEventRepository,
                                       ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
    }

    @KafkaListener(topics = "${app.kafka.topic.settlement-confirmed}", groupId = CONSUMER_GROUP, containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onSettlementConfirmed(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() { return CONSUMER_GROUP; }

    @Override
    protected String eventType() { return "SettlementConfirmed"; }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        // GL 무전표 — Option A 에서 확정은 전기하지 않는다. 멱등 마커 저장·ack 는 골격이 수행한다.
        log.info("정산확정 수신 — GL 무전표(Option A, 상계는 payout.completed 에서). eventId={}", eventId);
    }
}
