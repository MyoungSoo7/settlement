package github.lms.lemuel.company.adapter.out.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.company.domain.ArticleSentiment;
import github.lms.lemuel.company.domain.IssueCategory;
import github.lms.lemuel.company.domain.ReputationGrade;
import github.lms.lemuel.company.domain.ReputationScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CompanyReputationEventPublisherAdapterTest {

    private final SaveOutboxEventPort saveOutboxEventPort = mock(SaveOutboxEventPort.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CompanyReputationEventPublisherAdapter adapter =
            new CompanyReputationEventPublisherAdapter(saveOutboxEventPort, objectMapper);

    private static ReputationScore score() {
        // 2건 중 1건 FINANCIAL 부정 → 가중 3 / (2*3) → 50점(C)
        return ReputationScore.compute("005930", LocalDate.of(2026, 7, 7), List.of(
                ArticleSentiment.negative(IssueCategory.FINANCIAL), ArticleSentiment.positive()),
                Instant.parse("2026-07-07T09:00:00Z"));
    }

    @Test
    @DisplayName("Outbox 이벤트를 Company/CompanyReputationChanged 로 기록하고 종목코드를 aggregateId 로 쓴다")
    void writesOutboxEventWithConventionalRouting() {
        adapter.publishReputationChanged(score(), ReputationGrade.B);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(saveOutboxEventPort).save(captor.capture());
        OutboxEvent event = captor.getValue();

        assertEquals("Company", event.getAggregateType());
        assertEquals("CompanyReputationChanged", event.getEventType());
        assertEquals("005930", event.getAggregateId());
        assertTrue(event.isPending());
    }

    @Test
    @DisplayName("페이로드에 등급·직전등급·점수·집계가 담기고 java.time 은 문자열이다")
    void payloadShape() throws Exception {
        adapter.publishReputationChanged(score(), ReputationGrade.B);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(saveOutboxEventPort).save(captor.capture());
        JsonNode payload = objectMapper.readTree(captor.getValue().getPayload());

        assertEquals("005930", payload.get("stockCode").asText());
        assertEquals("2026-07-07", payload.get("snapshotDate").asText());
        assertEquals(50, payload.get("score").asInt());
        assertEquals("C", payload.get("grade").asText());
        assertEquals("B", payload.get("previousGrade").asText());
        assertEquals(2, payload.get("articleCount").asInt());
        assertEquals(1, payload.get("negativeCount").asInt());
        assertEquals("2026-07-07T09:00:00Z", payload.get("calculatedAt").asText());
    }

    @Test
    @DisplayName("최초 스냅샷은 previousGrade 가 null 로 직렬화된다")
    void nullPreviousGrade() throws Exception {
        adapter.publishReputationChanged(score(), null);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(saveOutboxEventPort).save(captor.capture());
        JsonNode payload = objectMapper.readTree(captor.getValue().getPayload());

        assertTrue(payload.get("previousGrade").isNull());
    }
}
