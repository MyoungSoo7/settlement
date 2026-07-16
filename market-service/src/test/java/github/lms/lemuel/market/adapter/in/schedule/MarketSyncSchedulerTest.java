package github.lms.lemuel.market.adapter.in.schedule;

import github.lms.lemuel.market.adapter.in.web.SyncStatusTracker;
import github.lms.lemuel.market.adapter.in.web.SyncStatusTracker.State;
import github.lms.lemuel.market.application.port.in.SyncQuotesUseCase;
import github.lms.lemuel.market.application.port.in.SyncResult;
import github.lms.lemuel.market.application.port.out.KrxClientPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketSyncSchedulerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    // 기준일 고정 2026-07-16 → lookback 3 이면 07-15·07-14·07-13 재조회
    private static final Clock CLOCK = Clock.fixed(
            LocalDate.of(2026, 7, 16).atStartOfDay(ZONE).toInstant(), ZONE);

    @Mock
    private SyncQuotesUseCase syncQuotesUseCase;
    @Mock
    private KrxClientPort krxClient;

    private SyncStatusTracker tracker;
    private MarketSyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        tracker = new SyncStatusTracker(new SimpleMeterRegistry());
        scheduler = new MarketSyncScheduler(syncQuotesUseCase, krxClient, tracker, 3, CLOCK);
    }

    @Test
    @DisplayName("KRX 키 미설정이면 수집 호출 없이 skip — 트래커 IDLE")
    void skipsWhenNotConfigured() {
        when(krxClient.isConfigured()).thenReturn(false);

        scheduler.runDailySync();

        verifyNoInteractions(syncQuotesUseCase);
        assertThat(tracker.current().state()).isEqualTo(State.IDLE);
    }

    @Test
    @DisplayName("키 설정 시 lookback 일수만큼 최근 거래일을 재조회하고 트래커 DONE")
    void syncsLookbackWindow() {
        when(krxClient.isConfigured()).thenReturn(true);
        when(syncQuotesUseCase.syncQuotes(any())).thenReturn(new SyncResult(10, 10, 0, 0));

        scheduler.runDailySync();

        verify(syncQuotesUseCase, times(3)).syncQuotes(any());
        verify(syncQuotesUseCase).syncQuotes(LocalDate.of(2026, 7, 15));
        verify(syncQuotesUseCase).syncQuotes(LocalDate.of(2026, 7, 13));
        assertThat(tracker.current().state()).isEqualTo(State.DONE);
    }

    @Test
    @DisplayName("수집 예외는 삼키고 트래커 FAILED 로만 기록")
    void marksFailedOnException() {
        when(krxClient.isConfigured()).thenReturn(true);
        when(syncQuotesUseCase.syncQuotes(any())).thenThrow(new IllegalStateException("KRX 다운"));

        scheduler.runDailySync();

        assertThat(tracker.current().state()).isEqualTo(State.FAILED);
        assertThat(tracker.current().error()).contains("KRX 다운");
    }

    @Test
    @DisplayName("다른 동기화가 진행 중이면 이번 회차 skip — 동시 실행 방지")
    void skipsWhenAlreadyRunning() {
        when(krxClient.isConfigured()).thenReturn(true);
        tracker.tryStart("manual:quotes");

        scheduler.runDailySync();

        verifyNoInteractions(syncQuotesUseCase);
        assertThat(tracker.current().state()).isEqualTo(State.RUNNING);
        assertThat(tracker.current().job()).isEqualTo("manual:quotes");
    }
}
