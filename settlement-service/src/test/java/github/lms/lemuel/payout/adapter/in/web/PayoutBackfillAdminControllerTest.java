package github.lms.lemuel.payout.adapter.in.web;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.common.config.JacksonCompatConfig;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.payout.application.port.in.BackfillMissingPayoutsUseCase;
import github.lms.lemuel.payout.domain.PayoutBackfillReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PayoutBackfillAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JacksonCompatConfig.class)
class PayoutBackfillAdminControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean BackfillMissingPayoutsUseCase useCase;
    @MockitoBean AuditLogger auditLogger;

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO   = LocalDate.of(2026, 1, 31);

    @Test
    @DisplayName("GET /admin/payouts/backfill/status — 잔여 건수 조회")
    void statusEndpoint() throws Exception {
        when(useCase.status(FROM, TO)).thenReturn(PayoutBackfillReport.status(FROM, TO, 5));

        mockMvc.perform(get("/admin/payouts/backfill/status")
                        .param("from", "2026-01-01")
                        .param("to",   "2026-01-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remaining").value(5))
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.skipped").value(0));

        verify(useCase).status(FROM, TO);
    }

    @Test
    @DisplayName("GET /status — remaining=0 이면 complete=true")
    void statusEndpoint_zeroRemaining() throws Exception {
        when(useCase.status(FROM, TO)).thenReturn(PayoutBackfillReport.status(FROM, TO, 0));

        mockMvc.perform(get("/admin/payouts/backfill/status")
                        .param("from", "2026-01-01")
                        .param("to",   "2026-01-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.remaining").value(0));
    }

    @Test
    @DisplayName("POST /admin/payouts/backfill — 기본 pageSize(미지정) + 감사 기록")
    void backfillDefault() throws Exception {
        var report = PayoutBackfillReport.of(FROM, TO, 100, 10, 2, 1, 0, 1);
        when(useCase.backfill(eq(FROM), eq(TO), isNull())).thenReturn(report);

        mockMvc.perform(post("/admin/payouts/backfill")
                        .param("from", "2026-01-01")
                        .param("to",   "2026-01-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(10))
                .andExpect(jsonPath("$.skipped").value(2))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.remaining").value(0))
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.pagesCommitted").value(1));

        verify(useCase).backfill(FROM, TO, null);
        // 감사 추적: PAYOUT_BACKFILL_EXECUTED 가 audit_logs 에 기록됐는지 확인
        verify(auditLogger).record(
                eq(AuditAction.PAYOUT_BACKFILL_EXECUTED),
                eq("PayoutBackfill"),
                anyString(),
                anyString());
    }

    @Test
    @DisplayName("POST /admin/payouts/backfill — pageSize 파라미터 전달 + 감사 기록")
    void backfillWithPageSize() throws Exception {
        var report = PayoutBackfillReport.of(FROM, TO, 50, 5, 0, 0, 3, 1);
        when(useCase.backfill(eq(FROM), eq(TO), eq(50))).thenReturn(report);

        mockMvc.perform(post("/admin/payouts/backfill")
                        .param("from", "2026-01-01")
                        .param("to",   "2026-01-31")
                        .param("pageSize", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageSize").value(50))
                .andExpect(jsonPath("$.remaining").value(3))
                .andExpect(jsonPath("$.complete").value(false));

        verify(useCase).backfill(FROM, TO, 50);
        // 잔여가 있어도 감사 기록은 남는다
        verify(auditLogger).record(
                eq(AuditAction.PAYOUT_BACKFILL_EXECUTED),
                eq("PayoutBackfill"),
                anyString(),
                anyString());
    }

    @Test
    @DisplayName("POST /admin/payouts/backfill — from 파라미터 없으면 400")
    void backfillMissingFrom() throws Exception {
        mockMvc.perform(post("/admin/payouts/backfill")
                        .param("to", "2026-01-31"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /admin/payouts/backfill — to 파라미터 없으면 400")
    void backfillMissingTo() throws Exception {
        mockMvc.perform(post("/admin/payouts/backfill")
                        .param("from", "2026-01-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /admin/payouts/backfill/status — from 없으면 400")
    void statusMissingFrom() throws Exception {
        mockMvc.perform(get("/admin/payouts/backfill/status")
                        .param("to", "2026-01-31"))
                .andExpect(status().isBadRequest());
    }
}
