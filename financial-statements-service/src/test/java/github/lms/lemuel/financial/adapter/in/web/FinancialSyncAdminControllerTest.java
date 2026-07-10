package github.lms.lemuel.financial.adapter.in.web;

import github.lms.lemuel.financial.application.port.in.SyncCompaniesUseCase;
import github.lms.lemuel.financial.application.port.in.SyncResult;
import github.lms.lemuel.financial.application.port.in.SyncStatementsUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FinancialSyncAdminController — 202 백그라운드 트리거·409 중복·GET status 를 검증.
 * 실행기는 인라인(Runnable::run)으로 대체해 백그라운드 태스크 성공/실패 경로를 동기적으로 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class FinancialSyncAdminControllerTest {

    @Mock
    private SyncCompaniesUseCase syncCompaniesUseCase;
    @Mock
    private SyncStatementsUseCase syncStatementsUseCase;
    @Mock
    private SyncStatusTracker tracker;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TaskExecutor inline = Runnable::run;
        FinancialSyncAdminController controller = new FinancialSyncAdminController(
                syncCompaniesUseCase, syncStatementsUseCase, tracker, inline);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("POST /companies — 선점 성공 시 202 + 백그라운드 실행 후 complete")
    void syncCompaniesAccepted() throws Exception {
        SyncResult result = new SyncResult(5, 4, 4, 1);
        when(tracker.tryStart("companies")).thenReturn(true);
        when(syncCompaniesUseCase.syncCompanies()).thenReturn(result);

        mockMvc.perform(post("/admin/financial/sync/companies"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("동기화 시작: companies"))
                .andExpect(jsonPath("$.statusUrl").value("/admin/financial/sync/status"));

        verify(syncCompaniesUseCase).syncCompanies();
        verify(tracker).complete(result);
    }

    @Test
    @DisplayName("POST /companies — 백그라운드 태스크 예외 시 tracker.fail")
    void syncCompaniesTaskFails() throws Exception {
        when(tracker.tryStart("companies")).thenReturn(true);
        when(syncCompaniesUseCase.syncCompanies()).thenThrow(new IllegalStateException("boom"));

        mockMvc.perform(post("/admin/financial/sync/companies"))
                .andExpect(status().isAccepted());

        verify(tracker).fail("boom");
    }

    @Test
    @DisplayName("POST /companies — 이미 실행 중이면 409")
    void syncCompaniesConflict() throws Exception {
        when(tracker.tryStart("companies")).thenReturn(false);
        when(tracker.current()).thenReturn(new SyncStatusTracker.Status(
                SyncStatusTracker.State.RUNNING, "companies", null, null, null, null));

        mockMvc.perform(post("/admin/financial/sync/companies"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").exists());

        verify(syncCompaniesUseCase, never()).syncCompanies();
    }

    @Test
    @DisplayName("POST /statements/{year} — 202 + 연도 반영 실행")
    void syncStatementsAccepted() throws Exception {
        when(tracker.tryStart("statements-2024")).thenReturn(true);
        when(syncStatementsUseCase.syncStatements(2024)).thenReturn(new SyncResult(3, 3, 3, 0));

        mockMvc.perform(post("/admin/financial/sync/statements/2024"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("동기화 시작: statements-2024"));

        verify(syncStatementsUseCase).syncStatements(2024);
    }

    @Test
    @DisplayName("GET /status — 현재 상태 보드 반환")
    void statusBoard() throws Exception {
        when(tracker.current()).thenReturn(new SyncStatusTracker.Status(
                SyncStatusTracker.State.IDLE, null, null, null, null, null));

        mockMvc.perform(get("/admin/financial/sync/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("IDLE"));
    }
}
