package github.lms.lemuel.settlement.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementUserViewJpaEntity;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementUserViewRepository;
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
class UserRegisteredEventConsumerTest {

    @Mock SettlementUserViewRepository userViewRepository;
    @Mock ProcessedEventRepository processedEventRepository;
    final ObjectMapper objectMapper = new ObjectMapper();

    UserRegisteredEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new UserRegisteredEventConsumer(userViewRepository, processedEventRepository, objectMapper,
                new SettlementProjectionMetrics(new SimpleMeterRegistry()), null);
    }

    private ConsumerRecord<String, String> userRegisteredRecord(UUID eventId, String json) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("lemuel.user.registered", 0, 0L, null, json);
        record.headers().add("event_id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    @Test
    @DisplayName("Phase 3b: UserRegistered 소비 시 settlement_user_view(email) 프로젝션을 적재한다")
    void onUserRegistered_upsertsProjection() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(userViewRepository.findById(77L)).thenReturn(Optional.empty());

        Acknowledgment ack = mock(Acknowledgment.class);
        consumer.onUserRegistered(
                userRegisteredRecord(UUID.randomUUID(), "{\"userId\":77,\"email\":\"a@b.com\"}"), ack);

        ArgumentCaptor<SettlementUserViewJpaEntity> cap =
                ArgumentCaptor.forClass(SettlementUserViewJpaEntity.class);
        verify(userViewRepository).save(cap.capture());
        assertThat(cap.getValue().getUserId()).isEqualTo(77L);
        assertThat(cap.getValue().getEmail()).isEqualTo("a@b.com");
        verify(processedEventRepository).save(any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("Phase 3b: 이미 처리된 event_id 는 user 프로젝션을 다시 쓰지 않는다 (멱등)")
    void onUserRegistered_idempotentSkip() {
        when(processedEventRepository.existsById(any())).thenReturn(true);
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.onUserRegistered(userRegisteredRecord(UUID.randomUUID(), "{\"userId\":1}"), ack);

        verify(userViewRepository, never()).save(any());
        verify(ack).acknowledge();
    }
}
