package github.lms.lemuel.report.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.report.application.port.in.GenerateCashflowReportUseCase;
import github.lms.lemuel.report.application.port.out.RenderCashflowReportPdfPort;
import github.lms.lemuel.report.domain.BucketGranularity;
import github.lms.lemuel.report.domain.CashflowBucket;
import github.lms.lemuel.report.domain.CashflowReconciliation;
import github.lms.lemuel.report.domain.CashflowReport;
import github.lms.lemuel.report.domain.ReconciliationCheck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ReportController.class)
@AutoConfigureMockMvc(addFilters = false)
class ReportControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean GenerateCashflowReportUseCase generateCashflowReportUseCase;
    @MockitoBean RenderCashflowReportPdfPort renderCashflowReportPdfPort;

    @Test
    @DisplayName("GET /api/reports/cashflow — 일별 리포트 응답")
    void cashflowDaily() throws Exception {
        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to = LocalDate.of(2026, 4, 2);

        CashflowReconciliation recon = CashflowReconciliation.of(List.of(
                ReconciliationCheck.of("payments_minus_refunds_equals_settlement",
                        new BigDecimal("140000"), new BigDecimal("140000"),
                        "payments=150000 - refunds=10000 = expected=140000; settlement.net=135500 + commission=4500 = actual=140000")
        ));
        CashflowReport stub = CashflowReport.of(from, to, BucketGranularity.DAY, List.of(
                new CashflowBucket(LocalDate.of(2026, 4, 1), 2,
                        new BigDecimal("50000.00"), BigDecimal.ZERO,
                        new BigDecimal("1500.00"), new BigDecimal("48500.00")),
                new CashflowBucket(LocalDate.of(2026, 4, 2), 3,
                        new BigDecimal("100000.00"), new BigDecimal("10000.00"),
                        new BigDecimal("3000.00"), new BigDecimal("87000.00"))
        ), recon);
        when(generateCashflowReportUseCase.generate(any())).thenReturn(stub);

        mockMvc.perform(get("/api/reports/cashflow")
                        .param("from", "2026-04-01")
                        .param("to", "2026-04-02")
                        .param("groupBy", "day"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period.groupBy").value("day"))
                .andExpect(jsonPath("$.totals.transactionCount").value(5))
                .andExpect(jsonPath("$.totals.gmv").value(150000.00))
                .andExpect(jsonPath("$.totals.netSettlement").value(135500.00))
                .andExpect(jsonPath("$.buckets.length()").value(2))
                // LocalDate 직렬화 포맷은 app Jackson 설정에 의존하므로 구조만 확인
                .andExpect(jsonPath("$.buckets[0].transactionCount").value(2))
                .andExpect(jsonPath("$.buckets[1].transactionCount").value(3))
                .andExpect(jsonPath("$.reconciliation.matched").value(true))
                .andExpect(jsonPath("$.reconciliation.checksRun").value(1))
                .andExpect(jsonPath("$.reconciliation.mismatches.length()").value(0));
    }

    @Test
    @DisplayName("GET /api/reports/sellers/{id}/cashflow — 판매자 단위 리포트")
    void sellerCashflow() throws Exception {
        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to = LocalDate.of(2026, 4, 30);

        CashflowReport stub = CashflowReport.of(from, to, BucketGranularity.DAY, List.of(
                new CashflowBucket(LocalDate.of(2026, 4, 1), 1,
                        new BigDecimal("10000.00"), BigDecimal.ZERO,
                        new BigDecimal("300.00"), new BigDecimal("9700.00"))
        ), CashflowReconciliation.empty());
        when(generateCashflowReportUseCase.generate(any())).thenReturn(stub);

        mockMvc.perform(get("/api/reports/sellers/42/cashflow")
                        .param("from", "2026-04-01")
                        .param("to", "2026-04-30")
                        .param("groupBy", "day"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.gmv").value(10000.00))
                // 판매자 단위는 reconciliation 은 빈값
                .andExpect(jsonPath("$.reconciliation.checksRun").value(0))
                .andExpect(jsonPath("$.reconciliation.matched").value(true));
    }

    @Test
    @DisplayName("GET /api/reports/cashflow — 잘못된 groupBy 400")
    void cashflowInvalidGroupBy() throws Exception {
        mockMvc.perform(get("/api/reports/cashflow")
                        .param("from", "2026-04-01")
                        .param("to", "2026-04-02")
                        .param("groupBy", "quarter"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("GET /api/reports/cashflow — from 누락 시 400")
    void cashflowMissingFrom() throws Exception {
        mockMvc.perform(get("/api/reports/cashflow")
                        .param("to", "2026-04-02")
                        .param("groupBy", "day"))
                .andExpect(status().isBadRequest());
    }
}
