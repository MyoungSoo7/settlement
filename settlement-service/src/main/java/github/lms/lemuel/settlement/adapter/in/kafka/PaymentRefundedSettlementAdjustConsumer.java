package github.lms.lemuel.settlement.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.settlement.application.port.in.AdjustSettlementForRefundUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.exception.SettlementNotFoundException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * PaymentRefunded 이벤트 → 실제 정산 조정(역정산) 트리거.
 *
 * <p>{@link PaymentRefundedViewConsumer}(별도 그룹)가 프로젝션 뷰의 환불액만 갱신하는 것과 달리,
 * 이 컨슈머는 {@link AdjustSettlementForRefundUseCase} 를 호출해 settlements.net_amount 재계산·
 * holdback 우선 차감·SettlementAdjustment 감사 레코드·원장 역분개(refundId 있을 때)까지 반영한다.
 * 환불이 셀러 지급액에 실제로 닿는 유일한 프로덕션 경로다.
 *
 * <p>멱등 골격은 {@link IdempotentEventConsumer} 가 소유한다. 뷰 컨슈머와 그룹을 분리해 한쪽 실패가
 * 다른 쪽 처리를 막지 않는다(뷰 갱신은 성공했는데 조정만 DLT 로 빠지는 경우 등).
 *
 * <p>실패 정책(fail-loud): 정산 미존재(환불 이벤트가 정산 생성보다 먼저 도착)나 DONE 정산
 * (지급 완료 후 환불 — 도메인이 직접 차감을 금지)은 {@link #handle} 에서 예외를 전파해 재시도 후 DLT 로
 * 보낸다. 조용히 넘기면 "환불 미반영 전액 지급"이 재발하므로 DLT 알림·replay 로 드러나게 한다.
 *
 * <p>금액 해석: 신규 페이로드의 {@code refundAmount}(건별 delta)를 사용한다. 구버전 페이로드
 * (누적 {@code refundedAmount} 만 존재)는 정산의 기반영 누적치와의 차이로 delta 를 복원한다
 * — 이미 반영된 재전송은 조정 없이 {@link #handle} 을 반환하면 골격이 마커만 남겨 이중 차감을 막는다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class PaymentRefundedSettlementAdjustConsumer extends IdempotentEventConsumer {

    private static final String CONSUMER_GROUP = "lemuel-settlement-refund-adjust";

    private final AdjustSettlementForRefundUseCase adjustSettlementForRefundUseCase;
    private final LoadSettlementPort loadSettlementPort;

    public PaymentRefundedSettlementAdjustConsumer(AdjustSettlementForRefundUseCase adjustSettlementForRefundUseCase,
                                                   LoadSettlementPort loadSettlementPort,
                                                   ProcessedEventRepository processedEventRepository,
                                                   ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.adjustSettlementForRefundUseCase = adjustSettlementForRefundUseCase;
        this.loadSettlementPort = loadSettlementPort;
    }

    @KafkaListener(
            topics = "${app.kafka.topic.payment-refunded:lemuel.payment.refunded}",
            groupId = CONSUMER_GROUP,
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onPaymentRefunded(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return CONSUMER_GROUP;
    }

    @Override
    protected String eventType() {
        return "PaymentRefunded";
    }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        if (!node.hasNonNull("paymentId")) {
            throw new IllegalArgumentException("Missing paymentId, eventId=" + eventId);
        }
        Long paymentId = node.get("paymentId").asLong();
        Long refundId = node.hasNonNull("refundId") ? node.get("refundId").asLong() : null;

        BigDecimal delta = resolveRefundDelta(node, paymentId, eventId);
        if (delta == null || delta.signum() <= 0) {
            // 이미 반영된 레거시 재전송 등 — 조정 없이 반환하면 골격이 processed 마커만 남겨
            // poison 재시도를 막는다(이중 차감 방지).
            return;
        }

        // 정산 미존재(SettlementNotFoundException)·DONE 정산(IllegalStateException)은 그대로 전파
        // → 컨테이너 에러 핸들러가 재시도 후 DLT 라우팅 (fail-loud).
        Settlement adjusted = adjustSettlementForRefundUseCase.adjustSettlementForRefund(paymentId, delta, refundId);

        log.info("Settlement adjusted from PaymentRefunded. eventId={}, paymentId={}, refundId={}, delta={}, netAmount={}, status={}",
                eventId, paymentId, refundId, delta, adjusted.getNetAmount(), adjusted.getStatus());
    }

    /**
     * 이번 이벤트가 정산에 반영해야 할 환불 delta 를 결정한다.
     * 신규 페이로드는 refundAmount(건별)를 그대로 쓰고, 레거시 페이로드(누적 refundedAmount 만)는
     * "이벤트 누적치 − 정산 기반영 누적치"로 복원한다. 0 이하면 이미 반영된 것이므로 스킵 신호(0).
     */
    private BigDecimal resolveRefundDelta(JsonNode node, Long paymentId, UUID eventId) {
        if (node.hasNonNull("refundAmount")) {
            return new BigDecimal(node.get("refundAmount").asText());
        }
        if (!node.hasNonNull("refundedAmount")) {
            log.warn("Refund event without refundAmount/refundedAmount — nothing to adjust. eventId={}, paymentId={}",
                    eventId, paymentId);
            return BigDecimal.ZERO;
        }
        BigDecimal cumulative = new BigDecimal(node.get("refundedAmount").asText());
        // FOR UPDATE 로 잠가 두면 이어지는 UseCase 의 재조회(동일 트랜잭션)와 일관된 스냅샷을 본다.
        Settlement settlement = loadSettlementPort.findByPaymentIdForUpdate(paymentId)
                .orElseThrow(() -> new SettlementNotFoundException("Settlement not found for paymentId: " + paymentId));
        BigDecimal delta = cumulative.subtract(settlement.getRefundedAmount());
        if (delta.signum() <= 0) {
            log.info("Legacy refund event already applied (cumulative {} <= settled {}). eventId={}, paymentId={}",
                    cumulative, settlement.getRefundedAmount(), eventId, paymentId);
        }
        return delta;
    }
}
