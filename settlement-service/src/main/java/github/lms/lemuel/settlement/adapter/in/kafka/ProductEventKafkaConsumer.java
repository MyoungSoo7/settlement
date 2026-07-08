package github.lms.lemuel.settlement.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementProductViewJpaEntity;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementProductViewRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * ProductChanged 이벤트 → settlement 소유 상품 프로젝션(settlement_product_view) 적재 (ADR 0020 Phase 3b).
 *
 * <p>order products(name)를 @Immutable 매핑하던 read-model 을 이벤트 기반 로컬 프로젝션으로 대체.
 * 멱등 골격은 {@link IdempotentEventConsumer} 가 소유하고, 여기서는 뷰 매핑만 구현한다 —
 * 동일 productId 의 이름변경 이벤트는 서로 다른 event_id 로 순차 반영.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class ProductEventKafkaConsumer extends IdempotentEventConsumer {

    private static final String CONSUMER_GROUP = "lemuel-settlement";

    private final SettlementProductViewRepository productViewRepository;
    private final SettlementProjectionMetrics projectionMetrics;

    public ProductEventKafkaConsumer(SettlementProductViewRepository productViewRepository,
                                     ProcessedEventRepository processedEventRepository,
                                     ObjectMapper objectMapper,
                                     SettlementProjectionMetrics projectionMetrics) {
        super(processedEventRepository, objectMapper);
        this.productViewRepository = productViewRepository;
        this.projectionMetrics = projectionMetrics;
    }

    @KafkaListener(
            topics = "${app.kafka.topic.product-changed:lemuel.product.changed}",
            groupId = CONSUMER_GROUP,
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onProductChanged(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return CONSUMER_GROUP;
    }

    @Override
    protected String eventType() {
        return "ProductChanged";
    }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        if (!node.hasNonNull("productId")) {
            throw new IllegalArgumentException("Missing productId, eventId=" + eventId);
        }
        Long productId = node.get("productId").asLong();

        SettlementProductViewJpaEntity view = productViewRepository.findById(productId)
                .orElseGet(SettlementProductViewJpaEntity::new);
        view.setProductId(productId);
        view.setName(node.hasNonNull("name") ? node.get("name").asText() : null);
        view.setUpdatedAt(LocalDateTime.now());
        productViewRepository.save(view);

        log.info("settlement_product_view upserted. eventId={}, productId={}", eventId, productId);
    }

    @Override
    protected void afterProcessed(ConsumerRecord<String, String> record) {
        projectionMetrics.recordApply("product", record.timestamp());
    }
}
