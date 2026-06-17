package github.lms.lemuel.settlement.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventJpaEntity;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementProductViewJpaEntity;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementProductViewRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * ProductChanged 이벤트 → settlement 소유 상품 프로젝션(settlement_product_view) 적재 (ADR 0020 Phase 3b).
 *
 * <p>order products(name)를 @Immutable 매핑하던 read-model 을 이벤트 기반 로컬 프로젝션으로 대체.
 * (consumer_group, event_id) 멱등 — 동일 productId 의 이름변경 이벤트는 서로 다른 event_id 로 순차 반영.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class ProductEventKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProductEventKafkaConsumer.class);
    private static final String CONSUMER_GROUP = "lemuel-settlement";

    private final SettlementProductViewRepository productViewRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;
    private final SettlementProjectionMetrics projectionMetrics;

    public ProductEventKafkaConsumer(SettlementProductViewRepository productViewRepository,
                                     ProcessedEventRepository processedEventRepository,
                                     ObjectMapper objectMapper,
                                     SettlementProjectionMetrics projectionMetrics) {
        this.productViewRepository = productViewRepository;
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper;
        this.projectionMetrics = projectionMetrics;
    }

    @KafkaListener(
            topics = "${app.kafka.topic.product-changed:lemuel.product.changed}",
            groupId = CONSUMER_GROUP,
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onProductChanged(ConsumerRecord<String, String> record, Acknowledgment ack) {
        UUID eventId = extractEventId(record);
        if (eventId == null) {
            log.warn("Skipping product event without event_id header. topic={}, offset={}",
                    record.topic(), record.offset());
            ack.acknowledge();
            return;
        }

        ProcessedEventJpaEntity.ProcessedEventId key =
                new ProcessedEventJpaEntity.ProcessedEventId(CONSUMER_GROUP, eventId);
        if (processedEventRepository.existsById(key)) {
            log.info("Product event already processed, skipping. eventId={}", eventId);
            ack.acknowledge();
            return;
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(record.value());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON payload, eventId=" + eventId, e);
        }

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

        processedEventRepository.save(new ProcessedEventJpaEntity(CONSUMER_GROUP, eventId, "ProductChanged"));
        projectionMetrics.recordApply("product", record.timestamp());
        log.info("settlement_product_view upserted. eventId={}, productId={}", eventId, productId);
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
