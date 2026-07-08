package github.lms.lemuel.settlement.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementOrderViewJpaEntity;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementOrderViewRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * OrderCreated 이벤트 → settlement 소유 주문 프로젝션(settlement_order_view) 적재 (ADR 0020 Phase 3b).
 *
 * <p>order 의 orders 테이블을 @Immutable 로 직접 매핑하던 read-model 을 이벤트 기반 로컬 프로젝션으로
 * 대체하기 위한 적재 컨슈머. 멱등 골격(헤더추출·(consumer_group, event_id) 멱등·JSON 파싱·마커저장·ack)은
 * {@link IdempotentEventConsumer} 가 소유하고, 여기서는 뷰 매핑만 구현한다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class OrderEventKafkaConsumer extends IdempotentEventConsumer {

    private static final String CONSUMER_GROUP = "lemuel-settlement";

    private final SettlementOrderViewRepository orderViewRepository;
    private final SettlementProjectionMetrics projectionMetrics;

    public OrderEventKafkaConsumer(SettlementOrderViewRepository orderViewRepository,
                                   ProcessedEventRepository processedEventRepository,
                                   ObjectMapper objectMapper,
                                   SettlementProjectionMetrics projectionMetrics) {
        super(processedEventRepository, objectMapper);
        this.orderViewRepository = orderViewRepository;
        this.projectionMetrics = projectionMetrics;
    }

    @KafkaListener(
            topics = "${app.kafka.topic.order-created:lemuel.order.created}",
            groupId = CONSUMER_GROUP,
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onOrderCreated(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return CONSUMER_GROUP;
    }

    @Override
    protected String eventType() {
        return "OrderCreated";
    }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        if (!node.hasNonNull("orderId")) {
            throw new IllegalArgumentException("Missing orderId, eventId=" + eventId);
        }
        Long orderId = node.get("orderId").asLong();

        SettlementOrderViewJpaEntity view = orderViewRepository.findById(orderId)
                .orElseGet(SettlementOrderViewJpaEntity::new);
        view.setOrderId(orderId);
        view.setUserId(node.hasNonNull("userId") ? node.get("userId").asLong() : null);
        view.setProductId(node.hasNonNull("productId") ? node.get("productId").asLong() : null);
        view.setStatus(node.hasNonNull("status") ? node.get("status").asText() : null);
        view.setAmount(node.hasNonNull("amount") ? new BigDecimal(node.get("amount").asText()) : null);
        view.setCreatedAt(node.hasNonNull("createdAt") ? LocalDateTime.parse(node.get("createdAt").asText()) : null);
        view.setUpdatedAt(LocalDateTime.now());
        orderViewRepository.save(view);

        log.info("settlement_order_view upserted. eventId={}, orderId={}", eventId, orderId);
    }

    @Override
    protected void afterProcessed(ConsumerRecord<String, String> record) {
        projectionMetrics.recordApply("order", record.timestamp());
    }
}
