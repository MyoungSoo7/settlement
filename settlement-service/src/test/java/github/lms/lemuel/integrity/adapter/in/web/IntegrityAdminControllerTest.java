package github.lms.lemuel.integrity.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.integrity.application.port.in.IntegrityQueryUseCase;
import github.lms.lemuel.integrity.application.port.in.ProjectionReconciliationUseCase;
import github.lms.lemuel.integrity.domain.HoldbackStatusReport;
import github.lms.lemuel.integrity.domain.LedgerCompletenessReport;
import github.lms.lemuel.integrity.domain.PayoutReconReport;
import github.lms.lemuel.integrity.domain.ProcessedEventCount;
import github.lms.lemuel.integrity.domain.ProjectionDiffReport;
import github.lms.lemuel.integrity.domain.RefundAdjustmentReport;
import github.lms.lemuel.integrity.domain.StuckStateReport;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = IntegrityAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
class IntegrityAdminControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean IntegrityQueryUseCase useCase;
    @MockitoBean ProjectionReconciliationUseCase projectionUseCase;

    @Test
    @DisplayName("GET /admin/integrity/ledger-completeness — INV-5")
    void ledgerCompleteness() throws Exception {
        LedgerCompletenessReport report = LedgerCompletenessReport.of(
                LocalDate.of(2026, 4, 1), 30, 5L, new BigDecimal("100000"),
                5L, new BigDecimal("100000"), List.of(), 0L, List.of(), List.of(),
                0L, 0L, 0L);
        when(useCase.checkLedgerCompleteness(LocalDate.of(2026, 4, 1), null)).thenReturn(report);

        mockMvc.perform(get("/admin/integrity/ledger-completeness").param("date", "2026-04-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.confirmedSettlements").value(5));
    }

    @Test
    @DisplayName("GET /admin/integrity/ledger-completeness — graceMinutes 전달")
    void ledgerCompletenessWithGrace() throws Exception {
        LedgerCompletenessReport report = LedgerCompletenessReport.of(
                LocalDate.of(2026, 4, 1), 15, 0L, BigDecimal.ZERO,
                0L, BigDecimal.ZERO, List.of(1L), 0L, List.of(), List.of(),
                0L, 0L, 0L);
        when(useCase.checkLedgerCompleteness(LocalDate.of(2026, 4, 1), 15)).thenReturn(report);

        mockMvc.perform(get("/admin/integrity/ledger-completeness")
                        .param("date", "2026-04-01")
                        .param("graceMinutes", "15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.missingSettlementIds[0]").value(1));
    }

    @Test
    @DisplayName("GET /admin/integrity/payout-recon — INV-6")
    void payoutRecon() throws Exception {
        PayoutReconReport report = PayoutReconReport.of(
                LocalDate.of(2026, 4, 1), 5L, new BigDecimal("100000"),
                5L, new BigDecimal("100000"), 5L, List.of(), List.of(), List.of(), List.of());
        when(useCase.checkPayoutRecon(LocalDate.of(2026, 4, 1))).thenReturn(report);

        mockMvc.perform(get("/admin/integrity/payout-recon").param("date", "2026-04-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.completedPayouts").value(5));
    }

    @Test
    @DisplayName("GET /admin/integrity/holdback-status — INV-7")
    void holdbackStatus() throws Exception {
        HoldbackStatusReport report = HoldbackStatusReport.of(
                LocalDate.of(2026, 4, 1), 1L, new BigDecimal("5000"), List.of(2L),
                new BigDecimal("5000"), new BigDecimal("1000"), null);
        when(useCase.checkHoldbackStatus()).thenReturn(report);

        mockMvc.perform(get("/admin/integrity/holdback-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.overdueCount").value(1));
    }

    @Test
    @DisplayName("GET /admin/integrity/stuck — INV-11")
    void stuck() throws Exception {
        StuckStateReport report = StuckStateReport.of(
                60, LocalDate.of(2026, 4, 1), List.of(), List.of(), List.of(), List.of(), 0L, 0L);
        when(useCase.checkStuckStates(null)).thenReturn(report);

        mockMvc.perform(get("/admin/integrity/stuck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.thresholdMinutes").value(60));
    }

    @Test
    @DisplayName("GET /admin/integrity/stuck — thresholdMinutes 전달")
    void stuckWithThreshold() throws Exception {
        StuckStateReport report = StuckStateReport.of(
                30, LocalDate.of(2026, 4, 1), List.of(), List.of(), List.of(), List.of(), 0L, 0L);
        when(useCase.checkStuckStates(30)).thenReturn(report);

        mockMvc.perform(get("/admin/integrity/stuck").param("thresholdMinutes", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thresholdMinutes").value(30));
    }

    @Test
    @DisplayName("GET /admin/integrity/refund-adjustments — INV-8")
    void refundAdjustments() throws Exception {
        LocalDate from = LocalDate.of(2026, 3, 1);
        LocalDate to = LocalDate.of(2026, 3, 31);
        RefundAdjustmentReport report = RefundAdjustmentReport.of(
                from, to, 3L, new BigDecimal("15000"), 3L, List.of(), BigDecimal.ZERO, false);
        when(useCase.checkRefundAdjustments(from, to)).thenReturn(report);

        mockMvc.perform(get("/admin/integrity/refund-adjustments")
                        .param("from", "2026-03-01")
                        .param("to", "2026-03-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.completedRefunds").value(3));
    }

    @Test
    @DisplayName("GET /admin/integrity/processed-count — INV-10")
    void processedCount() throws Exception {
        LocalDate from = LocalDate.of(2026, 3, 1);
        LocalDate to = LocalDate.of(2026, 3, 31);
        when(useCase.processedEventCounts(from, to)).thenReturn(
                List.of(new ProcessedEventCount("settlement-consumer", "payment.captured", 42L)));

        mockMvc.perform(get("/admin/integrity/processed-count")
                        .param("from", "2026-03-01")
                        .param("to", "2026-03-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].consumerGroup").value("settlement-consumer"))
                .andExpect(jsonPath("$[0].count").value(42));
    }

    @Test
    @DisplayName("GET /admin/integrity/projection-diff — INV-12 누락 id 특정")
    void projectionDiff() throws Exception {
        LocalDate date = LocalDate.of(2026, 4, 1);
        ProjectionDiffReport report = ProjectionDiffReport.of(
                date, "payment", 3L, new BigDecimal("3000"), 2L, new BigDecimal("2000"),
                List.of(902L), new BigDecimal("1000"), 1L,
                List.of(), 0L, List.of(), 0L, false);
        when(projectionUseCase.reconcileProjection(eq(date), eq("payment"), isNull())).thenReturn(report);

        mockMvc.perform(get("/admin/integrity/projection-diff").param("date", "2026-04-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.checksumMatched").value(false))
                .andExpect(jsonPath("$.missingInProjectionIds[0]").value(902))
                .andExpect(jsonPath("$.missingInProjectionCount").value(1));
    }

    @Test
    @DisplayName("GET /admin/integrity/projection-diff — 체크섬 일치 시 통과")
    void projectionDiffMatched() throws Exception {
        LocalDate date = LocalDate.of(2026, 4, 1);
        ProjectionDiffReport report = ProjectionDiffReport.matched(date, "payment", 5L, new BigDecimal("5000"));
        when(projectionUseCase.reconcileProjection(eq(date), eq("payment"), eq(50))).thenReturn(report);

        mockMvc.perform(get("/admin/integrity/projection-diff")
                        .param("date", "2026-04-01")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.checksumMatched").value(true));
    }
}
