package github.lms.lemuel.pgreconciliation.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.pgreconciliation.application.port.in.ReconcilePgFileUseCase;
import github.lms.lemuel.pgreconciliation.application.port.in.ResolveDiscrepancyUseCase;
import github.lms.lemuel.pgreconciliation.application.port.out.LoadReconciliationRunPort;
import github.lms.lemuel.pgreconciliation.domain.DiscrepancyType;
import github.lms.lemuel.pgreconciliation.domain.ReconciliationDiscrepancy;
import github.lms.lemuel.pgreconciliation.domain.ReconciliationRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PgReconciliationController.class)
@AutoConfigureMockMvc(addFilters = false)
class PgReconciliationControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean ReconcilePgFileUseCase reconcileUseCase;
    @MockitoBean ResolveDiscrepancyUseCase resolveUseCase;
    @MockitoBean LoadReconciliationRunPort loadPort;

    private static ReconciliationRun sampleRun() {
        ReconciliationRun run = ReconciliationRun.start("TOSS", LocalDate.of(2026, 4, 1), "toss-0401.csv", "op1");
        run.assignId(1L);
        return run;
    }

    private static ReconciliationDiscrepancy sampleDiscrepancy() {
        ReconciliationDiscrepancy d = ReconciliationDiscrepancy.newDiscrepancy(
                1L, DiscrepancyType.AMOUNT_MISMATCH, 10L, "pg-tx-1",
                new BigDecimal("1000"), new BigDecimal("1500"));
        d.assignId(5L);
        return d;
    }

    @Test
    @DisplayName("POST /admin/pg-reconciliation/files — CSV 업로드 + 즉시 대사")
    void upload() throws Exception {
        ReconciliationRun run = sampleRun();
        when(reconcileUseCase.reconcile(eq("TOSS"), eq(LocalDate.of(2026, 4, 1)),
                anyString(), any(), anyString())).thenReturn(run);

        MockMultipartFile file = new MockMultipartFile(
                "file", "toss-0401.csv", "text/csv",
                "pg_transaction_id,amount,refunded_amount,fee,settled_date\n".getBytes());

        mockMvc.perform(multipart("/admin/pg-reconciliation/files")
                        .file(file)
                        .param("provider", "TOSS")
                        .param("targetDate", "2026-04-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.pgProvider").value("TOSS"))
                .andExpect(jsonPath("$.run.id").value(1));
    }

    @Test
    @DisplayName("POST /admin/pg-reconciliation/files — 빈 파일이면 400")
    void uploadEmptyFile() throws Exception {
        MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.csv", "text/csv", new byte[0]);

        mockMvc.perform(multipart("/admin/pg-reconciliation/files")
                        .file(emptyFile)
                        .param("provider", "TOSS")
                        .param("targetDate", "2026-04-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /admin/pg-reconciliation/runs — 최근 실행 목록")
    void recentRuns() throws Exception {
        when(loadPort.findRecent(20)).thenReturn(List.of(sampleRun()));

        mockMvc.perform(get("/admin/pg-reconciliation/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].run.pgProvider").value("TOSS"));
    }

    @Test
    @DisplayName("GET /admin/pg-reconciliation/runs/{runId} — 상세 + 차이 목록")
    void runDetailFound() throws Exception {
        ReconciliationRun run = sampleRun();
        run.complete(1, 1, 0, List.of(sampleDiscrepancy()));
        when(loadPort.findById(1L)).thenReturn(Optional.of(run));

        mockMvc.perform(get("/admin/pg-reconciliation/runs/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.status").value("COMPLETED"))
                .andExpect(jsonPath("$.discrepancies.length()").value(1))
                .andExpect(jsonPath("$.discrepancies[0].discrepancy.type").value("AMOUNT_MISMATCH"));
    }

    @Test
    @DisplayName("GET /admin/pg-reconciliation/runs/{runId} — 미존재 404")
    void runDetailNotFound() throws Exception {
        when(loadPort.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/pg-reconciliation/runs/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /admin/pg-reconciliation/discrepancies/{id}/approve — 승인")
    void approve() throws Exception {
        ReconciliationDiscrepancy d = sampleDiscrepancy();
        d.approve("op1", "확인함");
        when(resolveUseCase.approve(eq(5L), anyString(), any())).thenReturn(d);

        mockMvc.perform(post("/admin/pg-reconciliation/discrepancies/5/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"확인함\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discrepancy.status").value("APPROVED"));
    }

    @Test
    @DisplayName("POST /admin/pg-reconciliation/discrepancies/{id}/reject — 거절")
    void reject() throws Exception {
        ReconciliationDiscrepancy d = sampleDiscrepancy();
        d.reject("op1", "무시 처리");
        when(resolveUseCase.reject(eq(5L), anyString(), any())).thenReturn(d);

        mockMvc.perform(post("/admin/pg-reconciliation/discrepancies/5/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"무시 처리\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discrepancy.status").value("REJECTED"));
    }
}
