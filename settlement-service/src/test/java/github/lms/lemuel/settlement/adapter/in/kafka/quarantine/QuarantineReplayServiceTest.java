package github.lms.lemuel.settlement.adapter.in.kafka.quarantine;

import github.lms.lemuel.common.outbox.adapter.in.kafka.ConsumedEventQuarantine;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 격리 재처리 규칙 — republish 내용·event_id 부여·상태 전이·비정상 전이 차단 (P0-3 AC3). */
class QuarantineReplayServiceTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final QuarantinedEventRepository repository = mock(QuarantinedEventRepository.class);
    private final QuarantineReplayService service = new QuarantineReplayService(repository, kafkaTemplate);

    @BeforeEach
    void brokerAcksByDefault() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    private static QuarantinedEventJpaEntity quarantined(UUID eventId, ConsumedEventQuarantine.Cause cause) {
        return new QuarantinedEventJpaEntity("lemuel-settlement", "lemuel.user.registered", 0, 42L,
                eventId, cause, "detail", "{\"broken\":true}");
    }

    private static String headerValue(ProducerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("event_id 없던 격리(MISSING) 재처리 → 행 id 기반 결정적 UUID 부여(재시도 멱등) + REPLAYED 전이")
    void replayAssignsDeterministicEventIdWhenMissing() {
        QuarantinedEventJpaEntity row = quarantined(null, ConsumedEventQuarantine.Cause.MISSING_EVENT_ID);
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(row));

        UUID used = service.replay(1L);

        // 재시도·동시 호출이 같은 event_id 를 재생성해 processed_events 멱등에 흡수되도록 결정적이어야 한다
        assertThat(used).isEqualTo(
                UUID.nameUUIDFromBytes("quarantine-replay:1".getBytes(StandardCharsets.UTF_8)));
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, String> sent = captor.getValue();
        assertThat(sent.topic()).isEqualTo("lemuel.user.registered");
        assertThat(sent.value()).isEqualTo("{\"broken\":true}");
        assertThat(headerValue(sent, "event_id")).isEqualTo(used.toString());
        assertThat(row.getStatus()).isEqualTo(QuarantinedEventJpaEntity.Status.REPLAYED);
        assertThat(row.getReplayEventId()).isEqualTo(used);
    }

    @Test
    @DisplayName("기존 event_id 격리 재처리 → 원본 payload 그대로 + 기존 event_id 유지 (override 미지원, 위조 벡터 차단)")
    void replayKeepsOriginalEventIdAndOriginalPayload() {
        UUID original = UUID.randomUUID();
        QuarantinedEventJpaEntity row = quarantined(original, ConsumedEventQuarantine.Cause.INVALID_PAYLOAD);
        when(repository.findByIdForUpdate(2L)).thenReturn(Optional.of(row));

        UUID used = service.replay(2L);

        assertThat(used).isEqualTo(original);
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        // 원본 격리 바이트만 재발행 — 운영자가 payload 를 임의로 바꿔 상류 토픽에 위조 발행할 수 없다.
        assertThat(captor.getValue().value()).isEqualTo("{\"broken\":true}");
        assertThat(headerValue(captor.getValue(), "event_id")).isEqualTo(original.toString());
    }

    @Test
    @DisplayName("이미 REPLAYED 인 행 재처리 금지 — 상태 전이 규칙 위반은 예외")
    void replayRejectsAlreadyReplayedRow() {
        QuarantinedEventJpaEntity row = quarantined(UUID.randomUUID(), ConsumedEventQuarantine.Cause.INVALID_PAYLOAD);
        row.markReplayed(UUID.randomUUID());
        when(repository.findByIdForUpdate(3L)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.replay(3L)).isInstanceOf(IllegalStateException.class);
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("존재하지 않는 격리 id → IllegalArgumentException")
    void replayRejectsUnknownId() {
        when(repository.findByIdForUpdate(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.replay(9L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("브로커 전송 실패 시 REPLAYED 전이 없음 — 행이 NEW 로 남아 재시도 가능 (무유실)")
    void replayDoesNotTransitionWhenBrokerSendFails() {
        QuarantinedEventJpaEntity row = quarantined(UUID.randomUUID(), ConsumedEventQuarantine.Cause.INVALID_PAYLOAD);
        when(repository.findByIdForUpdate(4L)).thenReturn(Optional.of(row));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        assertThatThrownBy(() -> service.replay(4L)).isInstanceOf(IllegalStateException.class);

        assertThat(row.getStatus()).isEqualTo(QuarantinedEventJpaEntity.Status.NEW);
        assertThat(row.getReplayEventId()).isNull();
    }
}
