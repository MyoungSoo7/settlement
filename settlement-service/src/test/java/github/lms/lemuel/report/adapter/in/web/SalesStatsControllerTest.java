package github.lms.lemuel.report.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.report.application.port.in.QuerySalesStatsUseCase;
import github.lms.lemuel.report.application.port.in.QuerySalesStatsUseCase.SalesSummary;
import github.lms.lemuel.report.domain.CashflowTotals;
import github.lms.lemuel.report.domain.ReportPeriod;
import github.lms.lemuel.report.domain.SalesBreakdown;
import github.lms.lemuel.report.domain.SalesComparison;
import github.lms.lemuel.report.domain.SalesDimension;
import github.lms.lemuel.report.domain.SalesSlice;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SalesStatsController.class)
@AutoConfigureMockMvc(addFilters = false)
class SalesStatsControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean QuerySalesStatsUseCase querySalesStatsUseCase;

    private static CashflowTotals totals(long count, String gmv) {
        return new CashflowTotals(count, new BigDecimal(gmv), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal(gmv), BigDecimal.ZERO);
    }

    @Test
    @DisplayName("GET /api/reports/sales-stats/summary — 현재·직전 합계와 증감률을 돌려준다")
    void summary() throws Exception {
        ReportPeriod period = ReportPeriod.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        when(querySalesStatsUseCase.summary(any())).thenReturn(new SalesSummary(
                period, period.previous(),
                new SalesComparison(totals(20, "2000"), totals(10, "1000"))));

        mockMvc.perform(get("/api/reports/sales-stats/summary")
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period.days").value(31))
                .andExpect(jsonPath("$.previousPeriod.from").value("2025-12-01"))
                .andExpect(jsonPath("$.current.gmv").value(2000))
                .andExpect(jsonPath("$.previous.gmv").value(1000))
                .andExpect(jsonPath("$.growth.gmv").value(1.0000));
    }

    @Test
    @DisplayName("직전 기간이 비어 있으면 증감률은 null 로 나간다 — 화면이 0% 로 오독하지 않게")
    void summaryWithoutBaseline() throws Exception {
        ReportPeriod period = ReportPeriod.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        when(querySalesStatsUseCase.summary(any())).thenReturn(new SalesSummary(
                period, period.previous(),
                new SalesComparison(totals(3, "300"), totals(0, "0"))));

        mockMvc.perform(get("/api/reports/sales-stats/summary")
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.growth.gmv").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/reports/sales-stats/breakdown — 축별 구성비를 돌려준다")
    void breakdown() throws Exception {
        when(querySalesStatsUseCase.breakdown(any(), any(), anyInt())).thenReturn(
                SalesBreakdown.from(List.of(
                        new SalesSlice("CARD", 3, new BigDecimal("7500"),
                                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("7500")),
                        new SalesSlice("TRANSFER", 1, new BigDecimal("2500"),
                                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("2500")))));

        mockMvc.perform(get("/api/reports/sales-stats/breakdown")
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-31")
                        .param("dimension", "PAYMENT_METHOD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dimension").value("PAYMENT_METHOD"))
                .andExpect(jsonPath("$.totalGmv").value(10000))
                .andExpect(jsonPath("$.rows[0].label").value("CARD"))
                .andExpect(jsonPath("$.rows[0].sharePercent").value(75.00))
                .andExpect(jsonPath("$.rows[1].label").value("TRANSFER"));
    }

    @Test
    @DisplayName("기간이 역전되면 400 — 도메인 검증이 컨트롤러를 통과해 나온다")
    void reversedPeriodIsRejected() throws Exception {
        mockMvc.perform(get("/api/reports/sales-stats/summary")
                        .param("from", "2026-01-31")
                        .param("to", "2026-01-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("지원하지 않는 축이면 400 — enum 변환 실패가 500 이 되어선 안 된다")
    void unknownDimensionIsRejected() throws Exception {
        mockMvc.perform(get("/api/reports/sales-stats/breakdown")
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-31")
                        .param("dimension", "MOON_PHASE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("dimension 이 없으면 400 — 필수 파라미터다")
    void missingDimensionIsRejected() throws Exception {
        mockMvc.perform(get("/api/reports/sales-stats/breakdown")
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-31"))
                .andExpect(status().isBadRequest());
    }
}
