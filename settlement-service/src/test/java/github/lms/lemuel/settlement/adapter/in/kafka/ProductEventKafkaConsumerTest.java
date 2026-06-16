package github.lms.lemuel.settlement.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementProductViewJpaEntity;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementProductViewRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
class ProductEventKafkaConsumerTest {

    @Mock SettlementProductViewRepository productViewRepository;
    @Mock ProcessedEventRepository processedEventRepository;
    final ObjectMapper objectMapper = new ObjectMapper();

    ProductEventKafkaConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ProductEventKafkaConsumer(productViewRepository, processedEventRepository, objectMapper,
                new SettlementProjectionMetrics(new SimpleMeterRegistry()));
    }

    private ConsumerRecord<String, String> productChangedRecord(UUID eventId, String json) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("lemuel.product.changed", 0, 0L, null, json);
        record.headers().add("event_id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    @Test
    @DisplayName("Phase 3b: ProductChanged 소비 시 settlement_product_view(name) 프로젝션을 적재한다")
    void onProductChanged_upsertsProjection() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(productViewRepository.findById(9L)).thenReturn(Optional.empty());

        Acknowledgment ack = mock(Acknowledgment.class);
        consumer.onProductChanged(
                productChangedRecord(UUID.randomUUID(), "{\"productId\":9,\"name\":\"원목마루 A\"}"), ack);

        ArgumentCaptor<SettlementProductViewJpaEntity> cap =
                ArgumentCaptor.forClass(SettlementProductViewJpaEntity.class);
        verify(productViewRepository).save(cap.capture());
        assertThat(cap.getValue().getProductId()).isEqualTo(9L);
        assertThat(cap.getValue().getName()).isEqualTo("원목마루 A");
        verify(processedEventRepository).save(any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("Phase 3b: 이미 처리된 event_id 는 product 프로젝션을 다시 쓰지 않는다 (멱등)")
    void onProductChanged_idempotentSkip() {
        when(processedEventRepository.existsById(any())).thenReturn(true);
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.onProductChanged(productChangedRecord(UUID.randomUUID(), "{\"productId\":1}"), ack);

        verify(productViewRepository, never()).save(any());
        verify(ack).acknowledge();
    }
}
