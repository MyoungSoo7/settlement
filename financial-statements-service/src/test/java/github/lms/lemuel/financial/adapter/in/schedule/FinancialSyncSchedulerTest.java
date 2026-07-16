package github.lms.lemuel.financial.adapter.in.schedule;

import github.lms.lemuel.financial.adapter.in.web.SyncStatusTracker;
import github.lms.lemuel.financial.adapter.in.web.SyncStatusTracker.State;
import github.lms.lemuel.financial.application.port.in.SyncCompaniesUseCase;
import github.lms.lemuel.financial.application.port.in.SyncResult;
import github.lms.lemuel.financial.application.port.in.SyncStatementsUseCase;
import github.lms.lemuel.financial.application.port.out.DartClientPort;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinancialSyncSchedulerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    // 기준일 고정 2026-07-16 → recentYears 2 이면 2026·2025 재수집
    private static final Clock CLOCK = Clock.fixed(
            LocalDate.of(2026, 7, 16).atStartOfDay(ZONE).toInstant(), ZONE);

    @Mock
    private SyncStatementsUseCase syncStatementsUseCase;
    @Mock
    private SyncCompaniesUseCase syncCompaniesUseCase;
    @Mock
    private DartClientPort dartClient;

    private SyncStatusTracker tracker;
    private FinancialSyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        tracker = new SyncStatusTracker(new SimpleMeterRegistry());
        scheduler = new FinancialSyncScheduler(
                syncStatementsUseCase, syncCompaniesUseCase, dartClient, tracker, 2, CLOCK);
    }

    @Test
    @DisplayName("DART 키 미설정이면 재무제표 수집 없이 skip — 트래커 IDLE")
    void statementsSkipWhenNotConfigured() {
        when(dartClient.isConfigured()).thenReturn(false);

        scheduler.runStatementsSync();

        verifyNoInteractions(syncStatementsUseCase);
        assertThat(tracker.current().state()).isEqualTo(State.IDLE);
    }

    @Test
    @DisplayName("키 설정 시 최근 N개 연도(당해·전년)를 재수집하고 트래커 DONE")
    void statementsSyncRecentYears() {
        when(dartClient.isConfigured()).thenReturn(true);
        when(syncStatementsUseCase.syncStatements(anyInt())).thenReturn(new SyncResult(30, 30, 0, 0));

        scheduler.runStatementsSync();

        verify(syncStatementsUseCase).syncStatements(2026);
        verify(syncStatementsUseCase).syncStatements(2025);
        assertThat(tracker.current().state()).isEqualTo(State.DONE);
    }

    @Test
    @DisplayName("재무제표 수집 예외는 삼키고 트래커 FAILED 로만 기록")
    void statementsMarkFailedOnException() {
        when(dartClient.isConfigured()).thenReturn(true);
        when(syncStatementsUseCase.syncStatements(anyInt())).thenThrow(new IllegalStateException("DART 다운"));

        scheduler.runStatementsSync();

        assertThat(tracker.current().state()).isEqualTo(State.FAILED);
        assertThat(tracker.current().error()).contains("DART 다운");
    }

    @Test
    @DisplayName("다른 동기화 진행 중이면 재무제표 회차 skip")
    void statementsSkipWhenAlreadyRunning() {
        when(dartClient.isConfigured()).thenReturn(true);
        tracker.tryStart("manual:statements");

        scheduler.runStatementsSync();

        verifyNoInteractions(syncStatementsUseCase);
        assertThat(tracker.current().job()).isEqualTo("manual:statements");
    }

    @Test
    @DisplayName("키 미설정이면 기업목록 수집 없이 skip")
    void companiesSkipWhenNotConfigured() {
        when(dartClient.isConfigured()).thenReturn(false);

        scheduler.runCompaniesSync();

        verifyNoInteractions(syncCompaniesUseCase);
        assertThat(tracker.current().state()).isEqualTo(State.IDLE);
    }

    @Test
    @DisplayName("키 설정 시 기업목록 재수집하고 트래커 DONE")
    void companiesSync() {
        when(dartClient.isConfigured()).thenReturn(true);
        when(syncCompaniesUseCase.syncCompanies()).thenReturn(new SyncResult(200, 200, 0, 0));

        scheduler.runCompaniesSync();

        verify(syncCompaniesUseCase).syncCompanies();
        assertThat(tracker.current().state()).isEqualTo(State.DONE);
    }
}
