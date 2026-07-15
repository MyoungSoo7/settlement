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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ReputationAdminControllerTest {

    @Mock
    private RecalcReputationUseCase recalcReputationUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ReputationAdminController(recalcReputationUseCase, mock(RecordAuditPort.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    @DisplayName("POST /recalc — 전체 재계산 요약 200")
    void recalcAll() throws Exception {
        when(recalcReputationUseCase.recalcAll())
                .thenReturn(new RecalcReputationUseCase.RecalcSummary(10, 7, 2, 1));

        mockMvc.perform(post("/admin/company/reputation/recalc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companies").value(10))
                .andExpect(jsonPath("$.saved").value(7))
                .andExpect(jsonPath("$.skippedNoArticle").value(2))
                .andExpect(jsonPath("$.skippedExisting").value(1));
    }

    @Test
    @DisplayName("POST /recalc/{stockCode} — 스냅샷 생성 시 평판 응답 200")
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
