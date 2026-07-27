package github.lms.lemuel.company.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import github.lms.lemuel.company.application.port.in.RecalcReputationUseCase;
import github.lms.lemuel.company.audit.application.port.out.RecordAuditPort;
import github.lms.lemuel.company.domain.ArticleSentiment;
import github.lms.lemuel.company.domain.IssueCategory;
import github.lms.lemuel.company.domain.ReputationScore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ReputationAdminControllerTest {

    @Mock
    private RecalcReputationUseCase recalcReputationUseCase;

    private RecalcStatusTracker tracker;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        tracker = new RecalcStatusTracker();
        // 실행기를 인라인으로 둬 백그라운드 작업이 요청 처리 중에 끝나도록 — 검증을 결정론적으로 만든다.
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ReputationAdminController(
                        recalcReputationUseCase, tracker, Runnable::run, mock(RecordAuditPort.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    @DisplayName("POST /recalc — 즉시 202 + statusUrl (배치는 백그라운드)")
    void recalcAllAccepted() throws Exception {
        when(recalcReputationUseCase.recalcAll())
                .thenReturn(new RecalcReputationUseCase.RecalcSummary(10, 7, 2, 1));

        mockMvc.perform(post("/admin/company/reputation/recalc"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.statusUrl").value("/admin/company/reputation/recalc/status"));

        verify(recalcReputationUseCase).recalcAll();
        assertEquals(RecalcStatusTracker.State.DONE, tracker.current().state());
    }

    @Test
    @DisplayName("POST /recalc — 이미 실행 중이면 409")
    void recalcAllConflict() throws Exception {
        tracker.tryStart("all");

        mockMvc.perform(post("/admin/company/reputation/recalc"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("GET /recalc/status — 완료 후 DONE 과 요약을 노출")
    void statusAfterDone() throws Exception {
        when(recalcReputationUseCase.recalcAll())
                .thenReturn(new RecalcReputationUseCase.RecalcSummary(10, 7, 2, 1));
        mockMvc.perform(post("/admin/company/reputation/recalc"))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/admin/company/reputation/recalc/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DONE"))
                .andExpect(jsonPath("$.job").value("all"))
                .andExpect(jsonPath("$.result.companies").value(10))
                .andExpect(jsonPath("$.result.saved").value(7))
                .andExpect(jsonPath("$.result.skippedNoArticle").value(2))
                .andExpect(jsonPath("$.result.skippedExisting").value(1));
    }

    @Test
    @DisplayName("GET /recalc/status — 트리거 전에는 IDLE")
    void statusIdle() throws Exception {
        mockMvc.perform(get("/admin/company/reputation/recalc/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("IDLE"));
    }

    @Test
    @DisplayName("배치가 실패해도 202 는 이미 나갔고 상태는 FAILED — 폴링으로 확인된다")
    void recalcAllFailure() throws Exception {
        when(recalcReputationUseCase.recalcAll()).thenThrow(new IllegalStateException("gemini down"));

        mockMvc.perform(post("/admin/company/reputation/recalc"))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/admin/company/reputation/recalc/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.error").value("gemini down"));
    }

    @Test
    @DisplayName("POST /recalc/{stockCode} — 스냅샷 생성 시 평판 응답 200 (단건은 동기 유지)")
    void recalcOnePresent() throws Exception {
        ReputationScore score = ReputationScore.compute("005930", LocalDate.of(2026, 7, 7), List.of(
                ArticleSentiment.negative(IssueCategory.FINANCIAL), ArticleSentiment.positive()),
                Instant.parse("2026-07-07T09:00:00Z"));
        when(recalcReputationUseCase.recalcFor("005930")).thenReturn(Optional.of(score));

        mockMvc.perform(post("/admin/company/reputation/recalc/005930"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockCode").value("005930"))
                .andExpect(jsonPath("$.grade").value("C"));
    }

    @Test
    @DisplayName("POST /recalc/{stockCode} — 스냅샷 미생성 시 메시지 200")
    void recalcOneEmpty() throws Exception {
        when(recalcReputationUseCase.recalcFor("005930")).thenReturn(Optional.empty());

        mockMvc.perform(post("/admin/company/reputation/recalc/005930"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());
    }
}
