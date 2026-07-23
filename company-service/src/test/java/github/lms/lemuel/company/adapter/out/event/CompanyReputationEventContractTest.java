package github.lms.lemuel.company.adapter.out.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.company.application.port.out.LoadSellerLinkPort;
import github.lms.lemuel.company.domain.IssueCategory;
import github.lms.lemuel.company.domain.ReputationGrade;
import github.lms.lemuel.company.domain.ReputationScore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 프로듀서 계약 테스트 (ADR 0024) — company 가 loan 으로 발행하는 평판 등급 변동 이벤트가
 * shared-common 의 계약 스키마를 통과해야 한다. loan 의 신용 리스크 프로젝션이 이 계약에 의존한다.
 */
@ExtendWith(MockitoExtension.class)
class CompanyReputationEventContractTest {

    private static final String TOPIC = "lemuel.company.reputation_changed";

    @Mock SaveOutboxEventPort saveOutboxEventPort;
    @Mock LoadSellerLinkPort loadSellerLinkPort;
    @Captor ArgumentCaptor<OutboxEvent> outboxCaptor;

    CompanyReputationEventPublisherAdapter publisher;

    @BeforeEach
    void setUp() {
        publisher = new CompanyReputationEventPublisherAdapter(
                saveOutboxEventPort, loadSellerLinkPort, new ObjectMapper());
    }

    private String savedPayload() {
        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        return outboxCaptor.getValue().getPayload();
    }

    private static ReputationScore score() {
        return ReputationScore.rehydrate("005930", LocalDate.of(2026, 7, 10), 55,
                ReputationGrade.C, 24, 10, 9, 5,
                new EnumMap<>(IssueCategory.class), Instant.parse("2026-07-10T09:30:00Z"));
    }

    @Test
    @DisplayName("CompanyReputationChanged 페이로드는 lemuel.company.reputation_changed 계약을 만족한다")
    void reputationChanged_satisfiesContract() {
        when(loadSellerLinkPort.sellersOf(anyString())).thenReturn(List.of(777L, 1001L));

        publisher.publishReputationChanged(score(), ReputationGrade.B);

        EventContractValidator.assertValid(TOPIC, savedPayload());
    }

    @Test
    @DisplayName("최초 스냅샷(previousGrade null)·링크 셀러 없음(빈 배열) 페이로드도 계약을 만족한다")
    void reputationChanged_firstSnapshotWithoutSellers_satisfiesContract() {
        when(loadSellerLinkPort.sellersOf(anyString())).thenReturn(List.of());

        publisher.publishReputationChanged(score(), null);

        EventContractValidator.assertValid(TOPIC, savedPayload());
    }
}
