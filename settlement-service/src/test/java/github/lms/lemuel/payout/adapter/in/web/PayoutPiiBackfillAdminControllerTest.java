package github.lms.lemuel.payout.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.payout.application.port.in.ReencryptPayoutPiiUseCase;
import github.lms.lemuel.payout.domain.PayoutPiiBackfillReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PayoutPiiBackfillAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
class PayoutPiiBackfillAdminControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean ReencryptPayoutPiiUseCase useCase;

    @Test
    @DisplayName("GET /admin/payouts/pii/status — 평문 잔존 건수 조회")
    // 메서드명이 status 면 정적 임포트 MockMvcResultMatchers.status 를 가려 컴파일이 깨진다 — 다른 이름 사용.
    void statusEndpoint() throws Exception {
        when(useCase.remainingPlaintextCount()).thenReturn(PayoutPiiBackfillReport.status(3));

        mockMvc.perform(get("/admin/payouts/pii/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingPlaintext").value(3))
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.backfilled").value(0));
    }

    @Test
    @DisplayName("POST /admin/payouts/pii/reencrypt — 기본 페이지 크기(파라미터 없음)")
    void reencryptDefault() throws Exception {
        when(useCase.reencryptLegacyPlaintext(isNull()))
                .thenReturn(PayoutPiiBackfillReport.of(500, 1017, 0, 3));

        mockMvc.perform(post("/admin/payouts/pii/reencrypt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backfilled").value(1017))
                .andExpect(jsonPath("$.remainingPlaintext").value(0))
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.pagesCommitted").value(3));

        verify(useCase).reencryptLegacyPlaintext(isNull());
    }

    @Test
    @DisplayName("POST /admin/payouts/pii/reencrypt — pageSize 파라미터 전달")
    void reencryptWithPageSize() throws Exception {
        when(useCase.reencryptLegacyPlaintext(eq(100)))
                .thenReturn(PayoutPiiBackfillReport.of(100, 100, 5, 1));

        mockMvc.perform(post("/admin/payouts/pii/reencrypt").param("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageSize").value(100))
                .andExpect(jsonPath("$.remainingPlaintext").value(5))
                .andExpect(jsonPath("$.complete").value(false));

        verify(useCase).reencryptLegacyPlaintext(eq(100));
    }
}
