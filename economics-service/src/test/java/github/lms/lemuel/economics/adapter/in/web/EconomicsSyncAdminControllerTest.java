package github.lms.lemuel.economics.adapter.in.web;

import github.lms.lemuel.economics.application.port.in.SyncIndicatorsUseCase;
import github.lms.lemuel.economics.application.port.in.SyncResult;
import github.lms.lemuel.economics.audit.application.port.out.RecordAuditPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * EconomicsSyncAdminController — 202 백그라운드 트리거·409 중복·GET status 를 검증.
 * 실행기는 인라인(Runnable::run)으로 대체해 백그라운드 태스크 성공/실패 경로를 동기적으로 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class EconomicsSyncAdminControllerTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 6, 30);

    @Mock
    private SyncIndicatorsUseCase syncIndicatorsUseCase;
    @Mock
    private SyncStatusTracker tracker;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TaskExecutor inline = Runnable::run;
        EconomicsSyncAdminController controller =
                new EconomicsSyncAdminController(syncIndicatorsUseCase, tracker, inline, mock(RecordAuditPort.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("POST — code 미지정(all) 선점 성공 시 202 + 백그라운드 complete")
    void syncAllAccepted() throws Exception {
        SyncResult result = new SyncResult(4, 4, 40, 0);
        when(tracker.tryStart("all:2026-01-01~2026-06-30")).thenReturn(true);
        when(syncIndicatorsUseCase.syncIndicators(isNull(), eq(FROM), eq(TO))).thenReturn(result);

        mockMvc.perform(post("/admin/economics/sync")
                        .param("from", "2026-01-01").param("to", "2026-06-30"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("동기화 시작: all:2026-01-01~2026-06-30"))
                .andExpect(jsonPath("$.statusUrl").value("/admin/economics/sync/status"));

        verify(syncIndicatorsUseCase).syncIndicators(isNull(), eq(FROM), eq(TO));
        verify(tracker).complete(result);
    }

    @Test
    @DisplayName("POST — code 지정 + 백그라운드 예외 시 tracker.fail")
    void syncCodeTaskFails() throws Exception {
        when(tracker.tryStart("CPI:2026-01-01~2026-06-30")).thenReturn(true);
        when(syncIndicatorsUseCase.syncIndicators(eq("CPI"), eq(FROM), eq(TO)))
                .thenThrow(new IllegalStateException("boom"));

        mockMvc.perform(post("/admin/economics/sync")
                        .param("code", "CPI").param("from", "2026-01-01").param("to", "2026-06-30"))
                .andExpect(status().isAccepted());

        verify(tracker).fail("boom");
    }

    @Test
    @DisplayName("POST — 이미 실행 중이면 409")
    void syncConflict() throws Exception {
        when(tracker.tryStart("all:2026-01-01~2026-06-30")).thenReturn(false);
        when(tracker.current()).thenReturn(new SyncStatusTracker.Status(
                SyncStatusTracker.State.RUNNING, "all", null, null, null, null));

        mockMvc.perform(post("/admin/economics/sync")
                        .param("from", "2026-01-01").param("to", "2026-06-30"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").exists());

        verify(syncIndicatorsUseCase, never()).syncIndicators(isNull(), eq(FROM), eq(TO));
    }

    @Test
    @DisplayName("GET /status — 현재 상태 보드 반환")
    void statusBoard() throws Exception {
        when(tracker.current()).thenReturn(new SyncStatusTracker.Status(
                SyncStatusTracker.State.IDLE, null, null, null, null, null));

        mockMvc.perform(get("/admin/economics/sync/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("IDLE"));
    }
}
