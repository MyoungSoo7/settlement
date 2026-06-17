package github.lms.lemuel.settlement.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventJpaEntity;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementOrderViewJpaEntity;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementOrderViewRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * OrderCreated 이벤트 → settlement 소유 주문 프로젝션(settlement_order_view) 적재 (ADR 0020 Phase 3b).
 *
 * <p>order 의 orders 테이블을 @Immutable 로 직접 매핑하던 read-model 을 이벤트 기반 로컬 프로젝션으로
 * 대체하기 위한 적재 컨슈머. (consumer_group, event_id) 멱등.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class OrderEventKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventKafkaConsumer.class);
    private static final String CONSUMER_GROUP = "lemuel-settlement";

    private final SettlementOrderViewRepository orderViewRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;
    private final SettlementProjectionMetrics projectionMetrics;

    public OrderEventKafkaConsumer(SettlementOrderViewRepository orderViewRepository,
                                   ProcessedEventRepository processedEventRepository,
                                   ObjectMapper objectMapper,
                                   SettlementProjectionMetrics projectionMetrics) {
        this.orderViewRepository = orderViewRepository;
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper;
        this.projectionMetrics = projectionMetrics;
    }

    @KafkaListener(
            topics = "${app.kafka.topic.order-created:lemuel.order.created}",
            groupId = CONSUMER_GROUP,
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onOrderCreated(ConsumerRecord<String, String> record, Acknowledgment ack) {
        UUID eventId = extractEventId(record);
        if (eventId == null) {
            log.warn("Skipping order event without event_id header. topic={}, offset={}",
                    record.topic(), record.offset());
            ack.acknowledge();
            return;
        }

        ProcessedEventJpaEntity.ProcessedEventId key =
                new ProcessedEventJpaEntity.ProcessedEventId(CONSUMER_GROUP, eventId);
        if (processedEventRepository.existsById(key)) {
            log.info("Order event already processed, skipping. eventId={}", eventId);
            ack.acknowledge();
            return;
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(record.value());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON payload, eventId=" + eventId, e);
        }

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

        processedEventRepository.save(new ProcessedEventJpaEntity(CONSUMER_GROUP, eventId, "OrderCreated"));
        projectionMetrics.recordApply("order", record.timestamp());
        log.info("settlement_order_view upserted. eventId={}, orderId={}", eventId, orderId);
        ack.acknowledge();
    }

    private static UUID extractEventId(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader("event_id");
        if (header == null) return null;
        try {
            return UUID.fromString(new String(header.value(), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
