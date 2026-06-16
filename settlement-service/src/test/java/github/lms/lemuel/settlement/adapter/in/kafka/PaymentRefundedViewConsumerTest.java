package github.lms.lemuel.settlement.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementPaymentViewJpaEntity;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementPaymentViewRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentRefundedViewConsumerTest {

    @Mock SettlementPaymentViewRepository paymentViewRepository;
    @Mock ProcessedEventRepository processedEventRepository;
    final ObjectMapper objectMapper = new ObjectMapper();

    PaymentRefundedViewConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PaymentRefundedViewConsumer(paymentViewRepository, processedEventRepository, objectMapper);
    }

    private ConsumerRecord<String, String> refundedRecord(UUID eventId, String json) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("lemuel.payment.refunded", 0, 0L, null, json);
        record.headers().add("event_id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    @Test
    @DisplayName("Phase 3b-4: PaymentRefunded 소비 시 payment_view 의 환불액·상태를 갱신한다")
    void onPaymentRefunded_updatesView() {
        SettlementPaymentViewJpaEntity existing = new SettlementPaymentViewJpaEntity();
        existing.setPaymentId(3L);
        existing.setOrderId(30L);
        existing.setAmount(new BigDecimal("50000"));
        existing.setStatus("CAPTURED");
        existing.setRefundedAmount(BigDecimal.ZERO);
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(paymentViewRepository.findById(3L)).thenReturn(Optional.of(existing));

        Acknowledgment ack = mock(Acknowledgment.class);
        consumer.onPaymentRefunded(
                refundedRecord(UUID.randomUUID(), "{\"paymentId\":3,\"orderId\":30,\"refundedAmount\":\"20000\"}"), ack);

        ArgumentCaptor<SettlementPaymentViewJpaEntity> cap =
                ArgumentCaptor.forClass(SettlementPaymentViewJpaEntity.class);
        verify(paymentViewRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("REFUNDED");
        assertThat(cap.getValue().getRefundedAmount()).isEqualByComparingTo("20000");
        verify(processedEventRepository).save(any());
        verify(ack).acknowledge();
    }
}
