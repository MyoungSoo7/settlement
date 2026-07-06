package github.lms.lemuel.settlement.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.settlement.application.port.in.ReconcileDailyTotalsUseCase;
import github.lms.lemuel.settlement.domain.ReconciliationReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ReconciliationController.class)
@AutoConfigureMockMvc(addFilters = false)
class ReconciliationControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean ReconcileDailyTotalsUseCase reconcileUseCase;

    @Test
    @DisplayName("GET /admin/reconciliation runs daily reconciliation")
    void run() throws Exception {
        LocalDate date = LocalDate.of(2026, 4, 1);
        when(reconcileUseCase.reconcile(date)).thenReturn(ReconciliationReport.of(
                date,
                new BigDecimal("100000"), // 캡처 gross
                new BigDecimal("10000"),  // 환불
                new BigDecimal("96500"),  // 생성된 정산 net
                new BigDecimal("3500"),   // 생성된 정산 commission (3.5%)
                new BigDecimal("10000")   // 환불 조정
        ));

        mockMvc.perform(get("/admin/reconciliation").param("date", "2026-04-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetDate").exists())
                .andExpect(jsonPath("$.discrepancy").value(0.00))
                .andExpect(jsonPath("$.matched").value(true));
    }

    @Test
    @DisplayName("GET /admin/reconciliation requires date")
    void runMissingDate() throws Exception {
        mockMvc.perform(get("/admin/reconciliation"))
                .andExpect(status().isBadRequest());
    }
}
