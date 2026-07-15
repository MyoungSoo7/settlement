package github.lms.lemuel.company.adapter.in.web;

import github.lms.lemuel.company.application.port.in.CollectArticlesUseCase;
import github.lms.lemuel.company.application.port.in.CollectResult;
import github.lms.lemuel.company.audit.application.port.out.RecordAuditPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CompanyCollectAdminControllerTest {

    @Mock
    private CollectArticlesUseCase collectArticlesUseCase;
    @Mock
    private CollectStatusTracker tracker;

    // 동기 실행기 — 제출된 작업을 즉시 실행해 tracker.complete/fail 경로까지 커버한다.
    private final TaskExecutor executor = Runnable::run;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CompanyCollectAdminController(collectArticlesUseCase, tracker, executor, mock(RecordAuditPort.class)))
                .build();
    }

    @Test
    @DisplayName("POST / — 선점 성공 시 202 + 전체 수집 실행·complete 호출")
    void collectAll() throws Exception {
        CollectResult result = new CollectResult(3, 30, 25, 5);
        when(tracker.tryStart("all")).thenReturn(true);
        when(collectArticlesUseCase.collectAll()).thenReturn(result);

        mockMvc.perform(post("/admin/company/collect"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("수집 시작: all"))
                .andExpect(jsonPath("$.statusUrl").value("/admin/company/collect/status"));

        verify(collectArticlesUseCase).collectAll();
        verify(tracker).complete(result);
    }

    @Test
    @DisplayName("POST /{stockCode} — 단건 수집 실행·complete 호출")
    void collectOne() throws Exception {
        CollectResult result = new CollectResult(1, 10, 8, 2);
        when(tracker.tryStart("005930")).thenReturn(true);
        when(collectArticlesUseCase.collectFor("005930")).thenReturn(result);

        mockMvc.perform(post("/admin/company/collect/005930"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("수집 시작: 005930"));

        verify(collectArticlesUseCase).collectFor("005930");
        verify(tracker).complete(result);
    }

    @Test
    @DisplayName("POST / — 이미 실행 중이면 409")
    void conflict() throws Exception {
        when(tracker.tryStart("all")).thenReturn(false);
        when(tracker.current()).thenReturn(new CollectStatusTracker.Status(
                CollectStatusTracker.State.RUNNING, "other", Instant.now(), null, null, null));

        mockMvc.perform(post("/admin/company/collect"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("POST / — 수집 중 예외면 tracker.fail 로 기록한다")
    void failurePath() throws Exception {
        when(tracker.tryStart("all")).thenReturn(true);
        when(collectArticlesUseCase.collectAll()).thenThrow(new RuntimeException("boom"));

        mockMvc.perform(post("/admin/company/collect"))
                .andExpect(status().isAccepted());

        verify(tracker).fail("boom");
    }

    @Test
    @DisplayName("GET /status — 현재 상태 200")
    void statusEndpoint() throws Exception {
        when(tracker.current()).thenReturn(new CollectStatusTracker.Status(
                CollectStatusTracker.State.IDLE, null, null, null, null, null));

        mockMvc.perform(get("/admin/company/collect/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("IDLE"));
    }
}
