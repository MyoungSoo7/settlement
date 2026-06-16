package github.lms.lemuel.settlement.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventJpaEntity;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementOrderViewJpaEntity;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementOrderViewRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderEventKafkaConsumerTest {

    @Mock SettlementOrderViewRepository orderViewRepository;
    @Mock ProcessedEventRepository processedEventRepository;
    final ObjectMapper objectMapper = new ObjectMapper();

    OrderEventKafkaConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new OrderEventKafkaConsumer(orderViewRepository, processedEventRepository, objectMapper);
    }

    private ConsumerRecord<String, String> orderCreatedRecord(UUID eventId, String json) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("lemuel.order.created", 0, 0L, null, json);
        record.headers().add("event_id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    @Test
    @DisplayName("Phase 3b: OrderCreated 소비 시 settlement_order_view 프로젝션을 적재한다")
    void onOrderCreated_upsertsProjection() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(orderViewRepository.findById(500L)).thenReturn(Optional.empty());

        String json = "{\"orderId\":500,\"userId\":42,\"productId\":7,\"status\":\"PAID\","
                + "\"amount\":\"99000\",\"createdAt\":\"2026-06-16T10:00:00\"}";
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.onOrderCreated(orderCreatedRecord(UUID.randomUUID(), json), ack);

        ArgumentCaptor<SettlementOrderViewJpaEntity> cap =
                ArgumentCaptor.forClass(SettlementOrderViewJpaEntity.class);
        verify(orderViewRepository).save(cap.capture());
        SettlementOrderViewJpaEntity v = cap.getValue();
        assertThat(v.getOrderId()).isEqualTo(500L);
        assertThat(v.getUserId()).isEqualTo(42L);
        assertThat(v.getProductId()).isEqualTo(7L);
        assertThat(v.getStatus()).isEqualTo("PAID");
        assertThat(v.getAmount()).isEqualByComparingTo("99000");
        assertThat(v.getCreatedAt()).isNotNull();
        verify(processedEventRepository).save(any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("Phase 3b: 이미 처리된 event_id 는 프로젝션을 다시 쓰지 않는다 (멱등)")
    void onOrderCreated_idempotentSkip() {
        when(processedEventRepository.existsById(any())).thenReturn(true);
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.onOrderCreated(orderCreatedRecord(UUID.randomUUID(), "{\"orderId\":1}"), ack);

        verify(orderViewRepository, never()).save(any());
        verify(ack).acknowledge();
    }
}
