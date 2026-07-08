package github.lms.lemuel.loan.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventJpaEntity;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.loan.application.port.in.IngestCompanyReputationUseCase;
import github.lms.lemuel.loan.application.port.in.IngestCompanyReputationUseCase.IngestCompanyReputationCommand;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompanyReputationChangedConsumerTest {

    private final IngestCompanyReputationUseCase ingest = mock(IngestCompanyReputationUseCase.class);
    private final ProcessedEventRepository processed = mock(ProcessedEventRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CompanyReputationChangedConsumer consumer =
            new CompanyReputationChangedConsumer(ingest, processed, objectMapper);
    private final Acknowledgment ack = mock(Acknowledgment.class);

    private static final String PAYLOAD = """
            {"stockCode":"005930","snapshotDate":"2026-07-07","score":50,"grade":"C",
             "previousGrade":"B","articleCount":4,"negativeCount":2,"calculatedAt":"2026-07-07T09:00:00Z"}
            """;

    private static ConsumerRecord<String, String> record(UUID eventId, String payload) {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("lemuel.company.reputation_changed", 0, 0L, "005930", payload);
        if (eventId != null) {
            record.headers().add(new RecordHeader("event_id",
                    eventId.toString().getBytes(StandardCharsets.UTF_8)));
        }
        return record;
    }

    @Test
    @DisplayName("정상 이벤트는 프로젝션 적재 + processed_events 기록 + ack")
    void happyPath() {
        UUID eventId = UUID.randomUUID();
        when(processed.existsById(any())).thenReturn(false);

        consumer.onReputationChanged(record(eventId, PAYLOAD), ack);

        ArgumentCaptor<IngestCompanyReputationCommand> captor =
                ArgumentCaptor.forClass(IngestCompanyReputationCommand.class);
        verify(ingest).ingest(captor.capture());
        IngestCompanyReputationCommand cmd = captor.getValue();
        assertEquals("005930", cmd.stockCode());
        assertEquals(50, cmd.score());
        assertEquals("C", cmd.grade());
        assertEquals("B", cmd.previousGrade());
        assertEquals(LocalDate.of(2026, 7, 7), cmd.snapshotDate());
        assertEquals(java.util.List.of(), cmd.sellerIds());   // 페이로드에 sellerIds 없음 → 빈 리스트
        verify(processed).save(any(ProcessedEventJpaEntity.class));
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("페이로드의 sellerIds 배열을 명령으로 전달한다 (셀러별 프로젝션용)")
    void parsesSellerIds() {
        UUID eventId = UUID.randomUUID();
        when(processed.existsById(any())).thenReturn(false);
        String payload = """
                {"stockCode":"005930","snapshotDate":"2026-07-07","score":50,"grade":"C",
                 "previousGrade":"B","sellerIds":[7,9],"calculatedAt":"2026-07-07T09:00:00Z"}
                """;

        consumer.onReputationChanged(record(eventId, payload), ack);

        ArgumentCaptor<IngestCompanyReputationCommand> captor =
                ArgumentCaptor.forClass(IngestCompanyReputationCommand.class);
        verify(ingest).ingest(captor.capture());
        assertEquals(java.util.List.of(7L, 9L), captor.getValue().sellerIds());
    }

    @Test
    @DisplayName("이미 처리된 이벤트는 적재하지 않고 ack (멱등)")
    void idempotentSkip() {
        UUID eventId = UUID.randomUUID();
        when(processed.existsById(any())).thenReturn(true);

        consumer.onReputationChanged(record(eventId, PAYLOAD), ack);

        verify(ingest, never()).ingest(any());
        verify(processed, never()).save(any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("event_id 헤더가 없으면 스킵 + ack (재처리 불가 레코드)")
    void missingEventIdHeader() {
        consumer.onReputationChanged(record(null, PAYLOAD), ack);

        verify(ingest, never()).ingest(any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("previousGrade null 페이로드도 정상 파싱한다 (최초 스냅샷)")
    void nullPreviousGrade() {
        UUID eventId = UUID.randomUUID();
        when(processed.existsById(any())).thenReturn(false);
        String payload = """
                {"stockCode":"005930","snapshotDate":"2026-07-07","score":100,"grade":"A",
                 "previousGrade":null,"articleCount":1,"negativeCount":0,"calculatedAt":"2026-07-07T09:00:00Z"}
                """;

        consumer.onReputationChanged(record(eventId, payload), ack);

        ArgumentCaptor<IngestCompanyReputationCommand> captor =
                ArgumentCaptor.forClass(IngestCompanyReputationCommand.class);
        verify(ingest).ingest(captor.capture());
        assertEquals(null, captor.getValue().previousGrade());
    }
}
