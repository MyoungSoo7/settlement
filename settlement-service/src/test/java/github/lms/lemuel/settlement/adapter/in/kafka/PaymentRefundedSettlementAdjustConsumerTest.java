package github.lms.lemuel.settlement.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.settlement.application.port.in.AdjustSettlementForRefundUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 환불 이벤트 → 역정산 배선 컨슈머 단위 검증.
 * (뷰만 갱신하고 netAmount 를 건드리지 않던 구멍을 막는 컨슈머 — 반드시 UseCase 호출까지 이어져야 한다)
 */
@ExtendWith(MockitoExtension.class)
class PaymentRefundedSettlementAdjustConsumerTest {

    @Mock AdjustSettlementForRefundUseCase adjustUseCase;
    @Mock LoadSettlementPort loadSettlementPort;
    @Mock ProcessedEventRepository processedEventRepository;
    final ObjectMapper objectMapper = new ObjectMapper();

    PaymentRefundedSettlementAdjustConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PaymentRefundedSettlementAdjustConsumer(
                adjustUseCase, loadSettlementPort, processedEventRepository, objectMapper, null);
    }

    private ConsumerRecord<String, String> refundedRecord(UUID eventId, String json) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("lemuel.payment.refunded", 0, 0L, null, json);
        record.headers().add("event_id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    @Test
    @DisplayName("신규 페이로드: refundAmount(건별)·refundId 로 역정산 UseCase 를 호출한다")
    void newPayload_callsAdjustWithDeltaAndRefundId() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(adjustUseCase.adjustSettlementForRefund(eq(3L), any(), eq(77L)))
                .thenReturn(settlement());

        Acknowledgment ack = mock(Acknowledgment.class);
        consumer.onPaymentRefunded(refundedRecord(UUID.randomUUID(),
                "{\"paymentId\":3,\"orderId\":30,\"refundedAmount\":\"30000\",\"refundAmount\":\"20000\",\"refundId\":77}"), ack);

        verify(adjustUseCase).adjustSettlementForRefund(3L, new BigDecimal("20000"), 77L);
        verify(processedEventRepository).save(any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("멱등: 이미 처리한 event_id 는 UseCase 를 호출하지 않고 ack 만 한다")
    void duplicateEvent_skipsAdjustment() {
        when(processedEventRepository.existsById(any())).thenReturn(true);

        Acknowledgment ack = mock(Acknowledgment.class);
        consumer.onPaymentRefunded(refundedRecord(UUID.randomUUID(),
                "{\"paymentId\":3,\"refundAmount\":\"20000\",\"refundId\":77}"), ack);

        verify(adjustUseCase, never()).adjustSettlementForRefund(any(), any(), any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("레거시 페이로드(누적만): 정산 기반영 누적치와의 차이로 delta 를 복원해 조정한다")
    void legacyPayload_derivesDeltaFromCumulative() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        Settlement settled = settlement(new BigDecimal("10000")); // refundedAmount 10,000 기반영
        when(loadSettlementPort.findByPaymentIdForUpdate(3L)).thenReturn(Optional.of(settled));
        when(adjustUseCase.adjustSettlementForRefund(eq(3L), any(), eq(null))).thenReturn(settled);

        Acknowledgment ack = mock(Acknowledgment.class);
        consumer.onPaymentRefunded(refundedRecord(UUID.randomUUID(),
                "{\"paymentId\":3,\"orderId\":30,\"refundedAmount\":\"30000\"}"), ack);

        // 누적 30,000 − 기반영 10,000 = delta 20,000
        verify(adjustUseCase).adjustSettlementForRefund(3L, new BigDecimal("20000"), null);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("레거시 재전송(누적 ≤ 기반영): 조정 없이 processed 마킹 후 ack — 이중 차감 방지")
    void legacyReplay_alreadyApplied_skipsWithoutAdjustment() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        Settlement settled = settlement(new BigDecimal("30000"));
        when(loadSettlementPort.findByPaymentIdForUpdate(3L)).thenReturn(Optional.of(settled));

        Acknowledgment ack = mock(Acknowledgment.class);
        consumer.onPaymentRefunded(refundedRecord(UUID.randomUUID(),
                "{\"paymentId\":3,\"refundedAmount\":\"30000\"}"), ack);

        verify(adjustUseCase, never()).adjustSettlementForRefund(any(), any(), any());
        verify(processedEventRepository).save(any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("event_id 헤더 없는 이벤트는 스킵(ack)하고 UseCase 를 호출하지 않는다")
    void missingEventIdHeader_skips() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "lemuel.payment.refunded", 0, 0L, null, "{\"paymentId\":3,\"refundAmount\":\"1000\"}");

        Acknowledgment ack = mock(Acknowledgment.class);
        consumer.onPaymentRefunded(record, ack);

        verify(adjustUseCase, never()).adjustSettlementForRefund(any(), any(), any());
        verify(ack).acknowledge();
    }

    private Settlement settlement() {
        return settlement(BigDecimal.ZERO);
    }

    private Settlement settlement(BigDecimal refundedAmount) {
        return Settlement.rehydrate(
                1L, 3L, 30L,
                new BigDecimal("100000"), refundedAmount,
                new BigDecimal("3500"), null,
                new BigDecimal("96500"),
                github.lms.lemuel.settlement.domain.SettlementStatus.PROCESSING,
                LocalDate.now(), null, null, null, null, null,
                null, null, null, false, null);
    }
}
