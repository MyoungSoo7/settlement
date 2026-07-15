package github.lms.lemuel.loan.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.loan.application.port.in.GetCompanyReputationUseCase;
import github.lms.lemuel.loan.domain.CompanyReputation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 셀러 평판 프로젝션 조회 컨트롤러 — 존재 시 200 + 본문, 미수신 종목이면 204.
 */
@WebMvcTest(controllers = CompanyReputationController.class)
@AutoConfigureMockMvc(addFilters = false)
class CompanyReputationControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean GetCompanyReputationUseCase getCompanyReputationUseCase;

    @Test
    @DisplayName("GET /loans/company-reputation/{stockCode} — 존재하면 200 + 본문")
    void present() throws Exception {
        when(getCompanyReputationUseCase.byStockCode("005930")).thenReturn(Optional.of(
                CompanyReputation.of("005930", 55, "C", "B", LocalDate.of(2026, 7, 7))));

        mockMvc.perform(get("/loans/company-reputation/005930"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockCode").value("005930"))
                .andExpect(jsonPath("$.score").value(55))
                .andExpect(jsonPath("$.grade").value("C"))
                .andExpect(jsonPath("$.previousGrade").value("B"))
                .andExpect(jsonPath("$.snapshotDate").value("2026-07-07"));
    }

    @Test
    @DisplayName("GET /loans/company-reputation/{stockCode} — 이벤트 미수신이면 204")
    void absent() throws Exception {
        when(getCompanyReputationUseCase.byStockCode("000000")).thenReturn(Optional.empty());

        mockMvc.perform(get("/loans/company-reputation/000000"))
                .andExpect(status().isNoContent());
    }
}
