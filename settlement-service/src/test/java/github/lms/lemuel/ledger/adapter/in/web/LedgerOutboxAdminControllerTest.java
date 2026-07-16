package github.lms.lemuel.ledger.adapter.in.web;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.common.config.JacksonCompatConfig;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.ledger.application.port.in.RequeueFailedLedgerOutboxUseCase;
import github.lms.lemuel.ledger.domain.LedgerOutboxTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = LedgerOutboxAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JacksonCompatConfig.class)
class LedgerOutboxAdminControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean RequeueFailedLedgerOutboxUseCase useCase;
    @MockitoBean AuditLogger auditLogger;

    @Test
    @DisplayName("GET /admin/outbox/ledger/failed — 총 건수 + 목록")
    void listFailed() throws Exception {
        when(useCase.countFailed()).thenReturn(2L);
        when(useCase.listFailed(50)).thenReturn(List.of(LedgerOutboxTask.create(11L)));

        mockMvc.perform(get("/admin/outbox/ledger/failed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedCount").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].settlementId").value(11));
    }

    @Test
    @DisplayName("POST /admin/outbox/ledger/requeue-failed — 재큐 후 감사 기록")
    void requeueFailed() throws Exception {
        when(useCase.requeueFailed(100)).thenReturn(3);
        when(useCase.countFailed()).thenReturn(0L);

        mockMvc.perform(post("/admin/outbox/ledger/requeue-failed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requeued").value(3))
                .andExpect(jsonPath("$.remainingFailed").value(0));

        verify(auditLogger).record(eq(AuditAction.LEDGER_OUTBOX_REQUEUED), eq("LedgerOutbox"),
                eq("requeue-failed"), anyString());
    }

    @Test
    @DisplayName("limit 상한 초과 요청은 500 으로 캡핑되어 use case 에 전달된다")
    void requeueClampsLimit() throws Exception {
        when(useCase.requeueFailed(500)).thenReturn(500);
        when(useCase.countFailed()).thenReturn(10L);

        mockMvc.perform(post("/admin/outbox/ledger/requeue-failed").param("limit", "100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requeued").value(500));

        verify(useCase).requeueFailed(500);
    }
}
