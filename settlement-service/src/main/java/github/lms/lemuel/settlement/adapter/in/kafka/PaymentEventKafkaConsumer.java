package github.lms.lemuel.settlement.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementPaymentViewJpaEntity;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementPaymentViewRepository;
import github.lms.lemuel.settlement.application.port.in.CreateSettlementFromPaymentUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 결제 완료 이벤트 수신 → 정산 자동 생성 컨슈머.
 *
 * <p>이 컨슈머가 추가됨으로써 "배치 중심 정산" 구조가 "이벤트 기반 정산" 으로 전환된다.
 *
 * <p>멱등성 3 단 방어:
 *   1. outbox event_id UUID unique — 프로듀서 측 중복 발행 방지
 *   2. processed_events(consumer_group, event_id) PK — 컨슈머 측 재수신 방지({@link IdempotentEventConsumer})
 *   3. settlements.payment_id UNIQUE (V3) — 스키마 수준 최종 방어
 *
 * <p>장애 처리 (DLT):
 * <ul>
 *   <li>예외가 throw 되면 ack 하지 않고 {@link KafkaErrorHandlerConfig} 의 DefaultErrorHandler 로 위임.</li>
 *   <li>일시적 예외 → ExponentialBackOff(2s ×2, 3회) 재시도 → 끝나면 DLT</li>
 *   <li>독성 메시지 (JsonProcessingException, IllegalArgumentException, IllegalStateException)
 *       → 재시도 없이 즉시 DLT — 같은 파티션의 후속 메시지 stall 방지</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class PaymentEventKafkaConsumer extends IdempotentEventConsumer {

    private static final String CONSUMER_GROUP = "lemuel-settlement";

    private final CreateSettlementFromPaymentUseCase createSettlementFromPaymentUseCase;
    private final SettlementPaymentViewRepository paymentViewRepository;
    private final SettlementProjectionMetrics projectionMetrics;

    /**
     * [실습 전용] 메시지당 인위 처리 지연(ms). 기본 0 = 꺼짐(운영 무영향).
     * Kafka 컨슈머 lag 을 의도적으로 발생시켜 모니터링/스케일 실습을 하기 위한 토글.
     * env {@code APP_KAFKA_PRACTICE_CONSUMER_DELAY_MS} 로 켠다.
     */
    private final long practiceDelayMs;

    public PaymentEventKafkaConsumer(CreateSettlementFromPaymentUseCase createSettlementFromPaymentUseCase,
                                     ProcessedEventRepository processedEventRepository,
                                     SettlementPaymentViewRepository paymentViewRepository,
                                     ObjectMapper objectMapper,
                                     SettlementProjectionMetrics projectionMetrics,
                                     @Value("${app.kafka.practice.consumer-delay-ms:0}") long practiceDelayMs) {
        super(processedEventRepository, objectMapper);
        this.createSettlementFromPaymentUseCase = createSettlementFromPaymentUseCase;
        this.paymentViewRepository = paymentViewRepository;
        this.projectionMetrics = projectionMetrics;
        this.practiceDelayMs = practiceDelayMs;
    }

    @KafkaListener(
            topics = "${app.kafka.topic.payment-captured}",
            groupId = CONSUMER_GROUP,
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onPaymentCaptured(ConsumerRecord<String, String> record, Acknowledgment ack) {
        slowDownForPractice();   // [실습 전용] 기본 0 = no-op
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return CONSUMER_GROUP;
    }

    @Override
    protected String eventType() {
        return "PaymentCaptured";
    }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        Long paymentId = node.get("paymentId").asLong();
        Long orderId = node.get("orderId").asLong();

        // 금액은 이벤트에 없으면 조회해야 하지만, 정산 서비스가 payment_id 로 조회 가능 —
        // 현재 단순화를 위해 관례적으로 payload 의 amount 필드 존재 여부로 분기.
        BigDecimal amount = node.has("amount")
                ? new BigDecimal(node.get("amount").asText())
                : BigDecimal.ZERO;  // 0 이면 정산 서비스가 이후 보강 책임

        // Event-Carried State Transfer (ADR 0020 Phase 1) — 셀러 메타가 동봉됐으면 사용,
        // 없으면(구 이벤트/미할당) 정산 서비스가 order DB 조인으로 fallback.
        Long sellerId = node.hasNonNull("sellerId") ? node.get("sellerId").asLong() : null;
        String sellerTier = node.hasNonNull("sellerTier") ? node.get("sellerTier").asText() : null;
        String settlementCycle = node.hasNonNull("settlementCycle") ? node.get("settlementCycle").asText() : null;

        // 정산 생성 (내부에서 payment_id unique 로 추가 중복 방어)
        createSettlementFromPaymentUseCase.createSettlementFromPayment(
                paymentId, orderId, amount, sellerId, sellerTier, settlementCycle);

        // 로컬 결제 프로젝션 적재 (ADR 0020 Phase 2)
        LocalDateTime capturedAt = node.hasNonNull("capturedAt")
                ? LocalDateTime.parse(node.get("capturedAt").asText())
                : LocalDateTime.now();
        SettlementPaymentViewJpaEntity view = paymentViewRepository.findById(paymentId)
                .orElseGet(SettlementPaymentViewJpaEntity::new);
        view.setPaymentId(paymentId);
        view.setOrderId(orderId);
        view.setAmount(amount);
        view.setStatus("CAPTURED");
        view.setCapturedAt(capturedAt);
        view.setSellerId(sellerId);
        view.setSellerTier(sellerTier);
        view.setSettlementCycle(settlementCycle);
        view.setPaymentMethod(node.hasNonNull("paymentMethod") ? node.get("paymentMethod").asText() : null);
        view.setPgTransactionId(node.hasNonNull("pgTransactionId") ? node.get("pgTransactionId").asText() : null);
        if (view.getRefundedAmount() == null) {
            view.setRefundedAmount(BigDecimal.ZERO); // 환불 이벤트가 먼저 도착한 드문 경우 기존값 보존
        }
        view.setUpdatedAt(LocalDateTime.now());
        paymentViewRepository.save(view);

        log.info("Settlement created from Kafka event. eventId={}, paymentId={}", eventId, paymentId);
    }

    @Override
    protected void afterProcessed(ConsumerRecord<String, String> record) {
        projectionMetrics.recordApply("payment", record.timestamp());
    }

    /**
     * [실습 전용] 컨슈머를 의도적으로 느리게 만들어 lag 을 발생시킨다.
     * {@code practiceDelayMs <= 0} 이면 즉시 반환(운영 무영향).
     */
    private void slowDownForPractice() {
        if (practiceDelayMs <= 0) return;
        try {
            Thread.sleep(practiceDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
