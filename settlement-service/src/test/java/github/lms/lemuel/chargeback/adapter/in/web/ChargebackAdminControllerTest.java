package github.lms.lemuel.chargeback.adapter.in.web;

import github.lms.lemuel.chargeback.application.port.in.DecideChargebackUseCase;
import github.lms.lemuel.chargeback.application.port.in.OpenChargebackUseCase;
import github.lms.lemuel.chargeback.application.port.out.LoadChargebackPort;
import github.lms.lemuel.chargeback.domain.Chargeback;
import github.lms.lemuel.chargeback.domain.ChargebackReason;
import github.lms.lemuel.chargeback.domain.ChargebackSource;
import github.lms.lemuel.chargeback.domain.ChargebackStatus;
import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.common.config.JacksonCompatConfig;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.idempotency.adapter.out.persistence.ManualIdempotencyGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ChargebackAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JacksonCompatConfig.class)
class ChargebackAdminControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean OpenChargebackUseCase openUseCase;
    @MockitoBean DecideChargebackUseCase decideUseCase;
    @MockitoBean LoadChargebackPort loadPort;
    @MockitoBean ManualIdempotencyGuard idempotency;
    @MockitoBean AuditLogger auditLogger;

    private static Chargeback sampleChargeback() {
        Chargeback cb = Chargeback.open(10L, 1L, new BigDecimal("50000"),
                ChargebackReason.FRAUD, "미인지 결제", ChargebackSource.MANUAL, null);
        cb.assignId(7L);
        return cb;
    }

    @Test
    @DisplayName("POST /admin/chargebacks — 수동 등록")
    void openManual() throws Exception {
        when(openUseCase.open(any())).thenReturn(sampleChargeback());

        mockMvc.perform(post("/admin/chargebacks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentId":10,"settlementId":1,"amount":50000,
                                 "reasonCode":"FRAUD","reasonDetail":"미인지 결제"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chargeback.id").value(7))
                .andExpect(jsonPath("$.chargeback.reasonCode").value("FRAUD"))
                .andExpect(jsonPath("$.chargeback.source").value("MANUAL"));
    }

    @Test
    @DisplayName("GET /admin/chargebacks — 상태별 목록")
    void list() throws Exception {
        when(loadPort.findByStatus(ChargebackStatus.OPEN, 20)).thenReturn(List.of(sampleChargeback()));

        mockMvc.perform(get("/admin/chargebacks")
                        .param("status", "OPEN")
                        .param("max", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].chargeback.status").value("OPEN"));
    }

    @Test
    @DisplayName("GET /admin/chargebacks — 기본 status(OPEN)")
    void listDefaultStatus() throws Exception {
        when(loadPort.findByStatus(ChargebackStatus.OPEN, 20)).thenReturn(List.of());

        mockMvc.perform(get("/admin/chargebacks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("GET /admin/chargebacks/{id} — 상세 성공")
    void getFound() throws Exception {
        when(loadPort.findById(7L)).thenReturn(Optional.of(sampleChargeback()));

        mockMvc.perform(get("/admin/chargebacks/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chargeback.id").value(7));
    }

    @Test
    @DisplayName("GET /admin/chargebacks/{id} — 미존재 404")
    void getNotFound() throws Exception {
        when(loadPort.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/chargebacks/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /admin/chargebacks/{id}/accept — 셀러 책임 인정")
    void accept() throws Exception {
        Chargeback cb = sampleChargeback();
        cb.accept("admin1", "증빙 부족");
        when(decideUseCase.accept(eq(7L), anyString(), any())).thenReturn(cb);

        mockMvc.perform(post("/admin/chargebacks/7/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"증빙 부족\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chargeback.status").value("ACCEPTED"));

        verify(auditLogger).record(eq(AuditAction.CHARGEBACK_ACCEPTED), eq("Chargeback"), eq("7"), anyString());
    }

    @Test
    @DisplayName("POST /admin/chargebacks/{id}/reject — 셀러 증빙 인정")
    void reject() throws Exception {
        Chargeback cb = sampleChargeback();
        cb.reject("admin1", "증빙 확인됨");
        when(decideUseCase.reject(eq(7L), anyString(), any())).thenReturn(cb);

        mockMvc.perform(post("/admin/chargebacks/7/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"증빙 확인됨\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chargeback.status").value("REJECTED"));

        verify(auditLogger).record(eq(AuditAction.CHARGEBACK_REJECTED), eq("Chargeback"), eq("7"), anyString());
    }

    @Test
    @DisplayName("POST accept — 중복 Idempotency-Key 는 409 (조작 미실행)")
    void accept_duplicateIdempotencyKey_returnsConflict() throws Exception {
        when(idempotency.claim(eq("cb-accept-1"), anyString(), anyString())).thenReturn(false);

        mockMvc.perform(post("/admin/chargebacks/7/accept")
                        .header("Idempotency-Key", "cb-accept-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"증빙 부족\"}"))
                .andExpect(status().isConflict());

        verifyNoInteractions(decideUseCase);
    }

    @Test
    @DisplayName("POST accept — 새 Idempotency-Key 는 조작 실행 후 200")
    void accept_freshIdempotencyKey_executes() throws Exception {
        Chargeback cb = sampleChargeback();
        cb.accept("admin1", "증빙 부족");
        when(idempotency.claim(eq("cb-accept-2"), anyString(), anyString())).thenReturn(true);
        when(decideUseCase.accept(eq(7L), anyString(), any())).thenReturn(cb);

        mockMvc.perform(post("/admin/chargebacks/7/accept")
                        .header("Idempotency-Key", "cb-accept-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"증빙 부족\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chargeback.status").value("ACCEPTED"));
    }
}
