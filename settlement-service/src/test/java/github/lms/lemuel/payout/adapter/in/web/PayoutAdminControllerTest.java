package github.lms.lemuel.payout.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.payout.application.port.in.ExecutePayoutUseCase;
import github.lms.lemuel.payout.application.port.in.RetryFailedPayoutUseCase;
import github.lms.lemuel.payout.application.port.out.LoadPayoutPort;
import github.lms.lemuel.payout.domain.Payout;
import github.lms.lemuel.payout.domain.PayoutStatus;
import github.lms.lemuel.payout.domain.SellerBankAccount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PayoutAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
class PayoutAdminControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean LoadPayoutPort loadPort;
    @MockitoBean RetryFailedPayoutUseCase retryUseCase;
    @MockitoBean ExecutePayoutUseCase executeUseCase;

    private static Payout samplePayout() {
        SellerBankAccount account = new SellerBankAccount("KB", "1234567890", "홍길동");
        Payout p = Payout.requestFromSettlement(1L, 10L, new BigDecimal("48500"), account);
        p.assignId(100L);
        return p;
    }

    @Test
    @DisplayName("GET /admin/payouts/failed — FAILED 목록")
    void listFailed() throws Exception {
        Payout p = samplePayout();
        when(loadPort.findByStatus(PayoutStatus.FAILED, 20)).thenReturn(List.of(p));

        mockMvc.perform(get("/admin/payouts/failed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].payout.id").value(100))
                .andExpect(jsonPath("$[0].payout.account").value("****7890"))
                .andExpect(jsonPath("$[0].payout.bank").value("KB"));
    }

    @Test
    @DisplayName("GET /admin/payouts/pending — REQUESTED 목록")
    void listPending() throws Exception {
        Payout p = samplePayout();
        when(loadPort.findByStatus(PayoutStatus.REQUESTED, 20)).thenReturn(List.of(p));

        mockMvc.perform(get("/admin/payouts/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].payout.status").value("REQUESTED"));
    }

    @Test
    @DisplayName("GET /admin/payouts/{id} — 상세 조회 성공")
    void getFound() throws Exception {
        when(loadPort.findById(100L)).thenReturn(Optional.of(samplePayout()));

        mockMvc.perform(get("/admin/payouts/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payout.id").value(100))
                .andExpect(jsonPath("$.payout.settlementId").value(1))
                .andExpect(jsonPath("$.payout.sellerId").value(10));
    }

    @Test
    @DisplayName("GET /admin/payouts/{id} — 미존재 404")
    void getNotFound() throws Exception {
        when(loadPort.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/payouts/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /admin/payouts/{id}/retry — 재시도")
    void retry() throws Exception {
        when(retryUseCase.retry(eq(100L), anyString())).thenReturn(samplePayout());

        mockMvc.perform(post("/admin/payouts/100/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payout.id").value(100));
    }

    @Test
    @DisplayName("POST /admin/payouts/{id}/cancel — 영구 취소")
    void cancel() throws Exception {
        Payout p = samplePayout();
        when(retryUseCase.cancel(eq(100L), anyString(), eq("고객 요청"))).thenReturn(p);

        mockMvc.perform(post("/admin/payouts/100/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"고객 요청\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payout.id").value(100));
    }

    @Test
    @DisplayName("POST /admin/payouts/execute-now — 수동 즉시 실행")
    void executeNow() throws Exception {
        when(executeUseCase.executeAllPending())
                .thenReturn(new ExecutePayoutUseCase.ExecutionReport(3, 1, 0));

        mockMvc.perform(post("/admin/payouts/execute-now"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").value(3))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.limitedSkipped").value(0));
    }
}
