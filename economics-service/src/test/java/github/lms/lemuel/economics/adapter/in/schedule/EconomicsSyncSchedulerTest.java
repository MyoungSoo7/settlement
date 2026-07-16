package github.lms.lemuel.economics.adapter.in.schedule;

import github.lms.lemuel.economics.adapter.in.web.SyncStatusTracker;
import github.lms.lemuel.economics.adapter.in.web.SyncStatusTracker.State;
import github.lms.lemuel.economics.application.port.in.SyncIndicatorsUseCase;
import github.lms.lemuel.economics.application.port.in.SyncResult;
import github.lms.lemuel.economics.application.port.out.EcosClientPort;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EconomicsSyncSchedulerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    // 기준일 고정 2026-07-16 → lookback 30일이면 조회 구간 [2026-06-16, 2026-07-16]
    private static final Clock CLOCK = Clock.fixed(
            LocalDate.of(2026, 7, 16).atStartOfDay(ZONE).toInstant(), ZONE);

    @Mock
    private SyncIndicatorsUseCase syncIndicatorsUseCase;
    @Mock
    private EcosClientPort ecosClient;

    private SyncStatusTracker tracker;
    private EconomicsSyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        tracker = new SyncStatusTracker();
        scheduler = new EconomicsSyncScheduler(syncIndicatorsUseCase, ecosClient, tracker, 30, CLOCK);
    }

    @Test
    @DisplayName("ECOS 키 미설정이면 동기화 호출 없이 skip — 트래커도 그대로 IDLE")
    void skipsWhenNotConfigured() {
        when(ecosClient.isConfigured()).thenReturn(false);

        scheduler.runDailySync();

        verifyNoInteractions(syncIndicatorsUseCase);
        assertThat(tracker.current().state()).isEqualTo(State.IDLE);
    }

    @Test
    @DisplayName("키 설정 시 전체 지표를 최근 30일 구간으로 동기화하고 트래커 DONE")
    void syncsRecentWindowWhenConfigured() {
        when(ecosClient.isConfigured()).thenReturn(true);
        when(syncIndicatorsUseCase.syncIndicators(isNull(), any(), any()))
                .thenReturn(new SyncResult(4, 120, 0, 0));

        scheduler.runDailySync();

        verify(syncIndicatorsUseCase).syncIndicators(
                null, LocalDate.of(2026, 6, 16), LocalDate.of(2026, 7, 16));
        assertThat(tracker.current().state()).isEqualTo(State.DONE);
        assertThat(tracker.current().result()).isEqualTo(new SyncResult(4, 120, 0, 0));
    }

    @Test
    @DisplayName("동기화 예외는 삼키고 트래커 FAILED 로만 기록 — 다음 스케줄에 재시도 가능")
    void marksFailedOnException() {
        when(ecosClient.isConfigured()).thenReturn(true);
        when(syncIndicatorsUseCase.syncIndicators(isNull(), any(), any()))
                .thenThrow(new IllegalStateException("ECOS 다운"));

        scheduler.runDailySync();

        assertThat(tracker.current().state()).isEqualTo(State.FAILED);
        assertThat(tracker.current().error()).contains("ECOS 다운");
    }

    @Test
    @DisplayName("수동 동기화가 진행 중이면 이번 회차는 skip — 동시 실행 방지")
    void skipsWhenAlreadyRunning() {
        when(ecosClient.isConfigured()).thenReturn(true);
        tracker.tryStart("manual:all");   // 다른 동기화가 선점한 상태

        scheduler.runDailySync();

        verifyNoInteractions(syncIndicatorsUseCase);
        assertThat(tracker.current().state()).isEqualTo(State.RUNNING);
        assertThat(tracker.current().job()).isEqualTo("manual:all");
    }
}
