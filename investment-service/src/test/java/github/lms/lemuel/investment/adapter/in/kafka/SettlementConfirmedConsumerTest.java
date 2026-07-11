package github.lms.lemuel.investment.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventJpaEntity;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.investment.application.port.in.IngestConfirmedSettlementUseCase;
import github.lms.lemuel.investment.application.port.in.IngestConfirmedSettlementUseCase.IngestConfirmedSettlementCommand;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementConfirmedConsumerTest {

    private final IngestConfirmedSettlementUseCase ingest = mock(IngestConfirmedSettlementUseCase.class);
    private final ProcessedEventRepository processed = mock(ProcessedEventRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SettlementConfirmedConsumer consumer =
            new SettlementConfirmedConsumer(ingest, processed, objectMapper);
    private final Acknowledgment ack = mock(Acknowledgment.class);

    private static final String PAYLOAD = """
            {"settlementId":9001,"sellerId":777,"amount":"43425.50"}
            """;

    private static ConsumerRecord<String, String> record(UUID eventId, String payload) {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("lemuel.settlement.confirmed", 0, 0L, "777", payload);
        if (eventId != null) {
            record.headers().add(new RecordHeader("event_id",
                    eventId.toString().getBytes(StandardCharsets.UTF_8)));
        }
        return record;
    }

    @Test
    @DisplayName("정상 이벤트는 재원 적재 + processed_events 기록 + ack")
    void happyPath() {
        UUID eventId = UUID.randomUUID();
        when(processed.existsById(any())).thenReturn(false);

        consumer.onSettlementConfirmed(record(eventId, PAYLOAD), ack);

        ArgumentCaptor<IngestConfirmedSettlementCommand> captor =
                ArgumentCaptor.forClass(IngestConfirmedSettlementCommand.class);
        verify(ingest).ingest(captor.capture());
        IngestConfirmedSettlementCommand cmd = captor.getValue();
        assertThat(cmd.settlementId()).isEqualTo(9001L);
        assertThat(cmd.sellerId()).isEqualTo(777L);
        assertThat(cmd.amount()).isEqualByComparingTo("43425.50");
        verify(processed).save(any(ProcessedEventJpaEntity.class));
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("이미 처리된 이벤트는 적재하지 않고 ack (멱등)")
    void idempotentSkip() {
        UUID eventId = UUID.randomUUID();
        when(processed.existsById(any())).thenReturn(true);

        consumer.onSettlementConfirmed(record(eventId, PAYLOAD), ack);

        verify(ingest, never()).ingest(any());
        verify(processed, never()).save(any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("event_id 헤더가 없으면 스킵 + ack")
    void missingEventIdHeader() {
        consumer.onSettlementConfirmed(record(null, PAYLOAD), ack);

        verify(ingest, never()).ingest(any());
        verify(ack).acknowledge();
    }
}
