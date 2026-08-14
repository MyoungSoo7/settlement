package github.lms.lemuel.settlement.adapter.in.web.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.settlement.application.port.in.ReleaseHoldbackUseCase;
import github.lms.lemuel.settlement.application.port.in.ReleaseHoldbackUseCase.HoldbackReleasePreview;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 홀드백 해제 미리보기 콘솔 — 조회 전용이라 해제를 절대 트리거하지 않는다. */
class HoldbackPreviewAdminControllerTest {

    private ReleaseHoldbackUseCase useCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        useCase = mock(ReleaseHoldbackUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new HoldbackPreviewAdminController(useCase))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new ObjectMapper().findAndRegisterModules()))
                .build();
    }

    @Test @DisplayName("해제 예정 건수·총액과 잘림 여부를 돌려준다")
    void preview() throws Exception {
        when(useCase.previewReleasableOn(any(), anyInt())).thenReturn(new HoldbackReleasePreview(
                2, new BigDecimal("29100"), true,
                List.of(new ReleaseHoldbackUseCase.ReleasableLine(
                        1L, 100L, new BigDecimal("14550"), LocalDate.of(2026, 8, 7)))));

        mockMvc.perform(get("/admin/settlements/holdback-preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.totalAmount").value(29100))
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.lines[0].holdbackAmount").value(14550));
    }

    @Test @DisplayName("미리보기는 실제 해제를 절대 호출하지 않는다")
    void neverReleases() throws Exception {
        when(useCase.previewReleasableOn(any(), anyInt()))
                .thenReturn(new HoldbackReleasePreview(0, BigDecimal.ZERO, false, List.of()));

        mockMvc.perform(get("/admin/settlements/holdback-preview")).andExpect(status().isOk());

        verify(useCase, never()).releaseAllDueOn(any());
    }

    @Test @DisplayName("기준일을 지정하면 그 날짜로 조회한다 — 미래 해제분 사전 점검")
    void honorsDateParam() throws Exception {
        when(useCase.previewReleasableOn(any(), anyInt()))
                .thenReturn(new HoldbackReleasePreview(0, BigDecimal.ZERO, false, List.of()));

        mockMvc.perform(get("/admin/settlements/holdback-preview").param("date", "2026-09-01"))
                .andExpect(status().isOk());

        verify(useCase).previewReleasableOn(eq(LocalDate.of(2026, 9, 1)), anyInt());
    }
}
