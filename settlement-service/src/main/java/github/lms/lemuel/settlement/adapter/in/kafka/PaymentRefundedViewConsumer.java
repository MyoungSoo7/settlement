package github.lms.lemuel.settlement.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventJpaEntity;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementPaymentViewJpaEntity;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementPaymentViewRepository;
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
 * PaymentRefunded 이벤트 → settlement 결제 프로젝션(settlement_payment_view)의 환불액·상태 갱신
 * (ADR 0020 Phase 3b-4).
 *
 * <p>QueryDSL 대사(payment.refundedAmount vs settlement.refundedAmount)·ES refundedAmount 가
 * 컷오버 후 이 갱신값을 사용한다. (consumer_group, event_id) 멱등.
 * 별도 그룹(lemuel-settlement-payment-view)으로 정산 생성 컨슈머와 분리한다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class PaymentRefundedViewConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentRefundedViewConsumer.class);
    private static final String CONSUMER_GROUP = "lemuel-settlement-payment-view";

    private final SettlementPaymentViewRepository paymentViewRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;
    private final SettlementProjectionMetrics projectionMetrics;

    public PaymentRefundedViewConsumer(SettlementPaymentViewRepository paymentViewRepository,
                                       ProcessedEventRepository processedEventRepository,
                                       ObjectMapper objectMapper,
                                       SettlementProjectionMetrics projectionMetrics) {
        this.paymentViewRepository = paymentViewRepository;
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper;
        this.projectionMetrics = projectionMetrics;
    }

    @KafkaListener(
            topics = "${app.kafka.topic.payment-refunded:lemuel.payment.refunded}",
            groupId = CONSUMER_GROUP,
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onPaymentRefunded(ConsumerRecord<String, String> record, Acknowledgment ack) {
        UUID eventId = extractEventId(record);
        if (eventId == null) {
            log.warn("Skipping refund event without event_id header. topic={}, offset={}",
                    record.topic(), record.offset());
            ack.acknowledge();
            return;
        }

        ProcessedEventJpaEntity.ProcessedEventId key =
                new ProcessedEventJpaEntity.ProcessedEventId(CONSUMER_GROUP, eventId);
        if (processedEventRepository.existsById(key)) {
            log.info("Refund event already processed, skipping. eventId={}", eventId);
            ack.acknowledge();
            return;
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(record.value());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON payload, eventId=" + eventId, e);
        }

        if (!node.hasNonNull("paymentId")) {
            throw new IllegalArgumentException("Missing paymentId, eventId=" + eventId);
        }
        Long paymentId = node.get("paymentId").asLong();
        BigDecimal refundedAmount = node.hasNonNull("refundedAmount")
                ? new BigDecimal(node.get("refundedAmount").asText())
                : BigDecimal.ZERO;

        // 프로젝션이 아직 없으면(환불이 capture 프로젝션보다 먼저 도착) 최소 행 생성
        SettlementPaymentViewJpaEntity view = paymentViewRepository.findById(paymentId)
                .orElseGet(() -> {
                    SettlementPaymentViewJpaEntity v = new SettlementPaymentViewJpaEntity();
                    v.setPaymentId(paymentId);
                    if (node.hasNonNull("orderId")) v.setOrderId(node.get("orderId").asLong());
                    v.setAmount(BigDecimal.ZERO);
                    return v;
                });
        view.setStatus("REFUNDED");
        view.setRefundedAmount(refundedAmount);
        view.setUpdatedAt(LocalDateTime.now());
        paymentViewRepository.save(view);

        processedEventRepository.save(new ProcessedEventJpaEntity(CONSUMER_GROUP, eventId, "PaymentRefunded"));
        projectionMetrics.recordApply("payment", record.timestamp());
        log.info("settlement_payment_view refund applied. eventId={}, paymentId={}, refunded={}",
                eventId, paymentId, refundedAmount);
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
