package github.lms.lemuel.ledger.adapter.in.web;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.common.config.JacksonCompatConfig;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.ledger.application.port.in.BackfillMissingReverseUseCase;
import github.lms.lemuel.ledger.domain.LedgerReverseBackfillReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = LedgerReverseBackfillAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JacksonCompatConfig.class)
class LedgerReverseBackfillAdminControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean BackfillMissingReverseUseCase useCase;
    @MockitoBean AuditLogger auditLogger;

    @Test
    @DisplayName("GET /admin/backfill/ledger-reverse/status — 역분개 누락 건 수 조회")
    void statusEndpoint() throws Exception {
        when(useCase.statusMissingReverse())
                .thenReturn(LedgerReverseBackfillReport.status(5L));

        mockMvc.perform(get("/admin/backfill/ledger-reverse/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingMissing").value(5))
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.totalEnqueued").value(0))
                .andExpect(jsonPath("$.pagesCommitted").value(0));
    }

    @Test
    @DisplayName("GET /admin/backfill/ledger-reverse/status — 누락 없으면 complete=true")
    void statusCompleteWhenZero() throws Exception {
        when(useCase.statusMissingReverse())
                .thenReturn(LedgerReverseBackfillReport.status(0L));

        mockMvc.perform(get("/admin/backfill/ledger-reverse/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingMissing").value(0))
                .andExpect(jsonPath("$.complete").value(true));
    }

    @Test
    @DisplayName("POST /admin/backfill/ledger-reverse/run — pageSize 없이 기본값 사용 + 감사 기록")
    void runDefault() throws Exception {
        when(useCase.backfillMissingReverse(isNull()))
                .thenReturn(LedgerReverseBackfillReport.of(200, 3, 7, 0L, 5));

        mockMvc.perform(post("/admin/backfill/ledger-reverse/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enqueuedChargeback").value(3))
                .andExpect(jsonPath("$.enqueuedReconciliation").value(7))
                .andExpect(jsonPath("$.totalEnqueued").value(10))
                .andExpect(jsonPath("$.pagesCommitted").value(5))
                .andExpect(jsonPath("$.remainingMissing").value(0))
                .andExpect(jsonPath("$.complete").value(true));

        verify(useCase).backfillMissingReverse(isNull());
        // 감사 추적: LEDGER_REVERSE_BACKFILL_EXECUTED 가 audit_logs 에 기록됐는지 확인
        verify(auditLogger).record(
                eq(AuditAction.LEDGER_REVERSE_BACKFILL_EXECUTED),
                eq("LedgerReverseBackfill"),
                eq("run"),
                anyString());
    }

    @Test
    @DisplayName("POST /admin/backfill/ledger-reverse/run — pageSize 파라미터 전달 + 감사 기록")
    void runWithPageSize() throws Exception {
        when(useCase.backfillMissingReverse(eq(100)))
                .thenReturn(LedgerReverseBackfillReport.of(100, 2, 3, 2L, 1));

        mockMvc.perform(post("/admin/backfill/ledger-reverse/run").param("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageSize").value(100))
                .andExpect(jsonPath("$.remainingMissing").value(2))
                .andExpect(jsonPath("$.complete").value(false));

        verify(useCase).backfillMissingReverse(eq(100));
        // 잔여가 있어도 감사 기록은 남는다
        verify(auditLogger).record(
                eq(AuditAction.LEDGER_REVERSE_BACKFILL_EXECUTED),
                eq("LedgerReverseBackfill"),
                eq("run"),
                anyString());
    }
}
