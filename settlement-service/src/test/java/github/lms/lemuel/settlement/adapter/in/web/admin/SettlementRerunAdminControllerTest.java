package github.lms.lemuel.settlement.adapter.in.web.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.common.config.JacksonCompatConfig;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.settlement.application.port.in.RerunSettlementBatchUseCase;
import github.lms.lemuel.settlement.domain.exception.InvalidRerunRequestException;
import github.lms.lemuel.settlement.domain.rerun.SettlementRerunReport;
import github.lms.lemuel.settlement.domain.rerun.SettlementRerunScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SettlementRerunAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JacksonCompatConfig.class)
class SettlementRerunAdminControllerTest {

    private static final LocalDate TARGET = LocalDate.of(2026, 8, 5);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean RerunSettlementBatchUseCase useCase;
    @MockitoBean AuditLogger auditLogger;

    @Test
    @DisplayName("POST /admin/settlements/rerun — scope·targetDate 를 유스케이스로 전달하고 리포트 반환")
    void rerunReturnsReport() throws Exception {
        when(useCase.rerun(SettlementRerunScope.CONFIRM, TARGET)).thenReturn(
                new SettlementRerunReport(TARGET, List.of(
                        SettlementRerunReport.StepResult.succeeded(
                                SettlementRerunScope.CONFIRM, 12, "status=COMPLETED, read=20, confirmed=12"))));

        mockMvc.perform(post("/admin/settlements/rerun")
                        .param("scope", "CONFIRM")
                        .param("targetDate", "2026-08-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetDate").value("2026-08-05"))
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.totalAffected").value(12))
                .andExpect(jsonPath("$.steps[0].scope").value("CONFIRM"))
                .andExpect(jsonPath("$.steps[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.steps[0].affected").value(12));

        verify(useCase).rerun(SettlementRerunScope.CONFIRM, TARGET);
    }

    @Test
    @DisplayName("targetDate 미지정 시 null 로 위임 — 기본값(어제) 보정은 유스케이스 책임")
    void targetDateOptional() throws Exception {
        when(useCase.rerun(eq(SettlementRerunScope.ALL), isNull())).thenReturn(
                new SettlementRerunReport(TARGET, List.of()));

        mockMvc.perform(post("/admin/settlements/rerun").param("scope", "ALL"))
                .andExpect(status().isOk());

        verify(useCase).rerun(SettlementRerunScope.ALL, null);
    }

    @Test
    @DisplayName("부분 실패는 200 + complete=false 로 반환 — 실패 단계를 운영자가 식별할 수 있다")
    void partialFailureIsReportedNotThrown() throws Exception {
        when(useCase.rerun(any(), any())).thenReturn(
                new SettlementRerunReport(TARGET, List.of(
                        SettlementRerunReport.StepResult.failed(SettlementRerunScope.CONFIRM, "boom"),
                        SettlementRerunReport.StepResult.succeeded(
                                SettlementRerunScope.HOLDBACK_RELEASE, 3, "released=3"))));

        mockMvc.perform(post("/admin/settlements/rerun")
                        .param("scope", "ALL")
                        .param("targetDate", "2026-08-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.failedSteps[0]").value("CONFIRM"))
                .andExpect(jsonPath("$.totalAffected").value(3));
    }

    @Test
    @DisplayName("실행 이력은 audit_logs 에 SETTLEMENT_BATCH_RERUN 으로 기록")
    void recordsAuditLog() throws Exception {
        when(useCase.rerun(any(), any())).thenReturn(
                new SettlementRerunReport(TARGET, List.of(
                        SettlementRerunReport.StepResult.succeeded(SettlementRerunScope.CONFIRM, 12, "ok"))));

        mockMvc.perform(post("/admin/settlements/rerun")
                        .param("scope", "CONFIRM")
                        .param("targetDate", "2026-08-05"))
                .andExpect(status().isOk());

        verify(auditLogger).record(eq(AuditAction.SETTLEMENT_BATCH_RERUN), eq("SettlementRerun"),
                anyString(), anyString());
    }

    @Test
    @DisplayName("잘못된 scope 는 400 — 허용값을 벗어난 입력이 배치까지 내려가지 않는다")
    void invalidScopeRejected() throws Exception {
        mockMvc.perform(post("/admin/settlements/rerun")
                        .param("scope", "FOOBAR")
                        .param("targetDate", "2026-08-05"))
                .andExpect(status().isBadRequest());

        verify(useCase, never()).rerun(any(), any());
    }

    @Test
    @DisplayName("도메인 게이트 위반(미래 일자)은 400 으로 매핑")
    void domainGateViolationMapsTo400() throws Exception {
        when(useCase.rerun(any(), any()))
                .thenThrow(new InvalidRerunRequestException("미래 일자는 재실행할 수 없습니다"));

        mockMvc.perform(post("/admin/settlements/rerun")
                        .param("scope", "CONFIRM")
                        .param("targetDate", "2099-01-01"))
                .andExpect(status().isBadRequest());
    }
}
