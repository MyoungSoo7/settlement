package github.lms.lemuel.loan.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.loan.application.port.in.DisburseLoanUseCase;
import github.lms.lemuel.loan.application.port.in.RequestLoanUseCase;
import github.lms.lemuel.loan.application.port.out.LoadLoanPort;
import github.lms.lemuel.loan.domain.LoanAdvance;
import github.lms.lemuel.loan.domain.LoanStatus;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * loan-service 는 자체 @ControllerAdvice 가 없어 IllegalArgumentException 이 shared-common 의
 * GlobalExceptionHandler 로 처리되어야 한다. 과거에는 공통 핸들러에 IllegalArgumentException 매핑이
 * 없어 500(INTERNAL_SERVER_ERROR)으로 누수되던 경로를 400 으로 고정하는 회귀 가드.
 */
@WebMvcTest(controllers = LoanController.class)
@AutoConfigureMockMvc(addFilters = false)
class LoanControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean RequestLoanUseCase requestLoanUseCase;
    @MockitoBean DisburseLoanUseCase disburseLoanUseCase;
    @MockitoBean LoadLoanPort loadLoanPort;

    @Test
    @DisplayName("POST /loans — 도메인 검증 실패(IllegalArgumentException)는 500 이 아닌 400 으로 매핑된다")
    void requestLoanInvalidArgumentMapsTo400() throws Exception {
        when(requestLoanUseCase.request(any()))
                .thenThrow(new IllegalArgumentException("financing days exceed limit"));

        mockMvc.perform(post("/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sellerId":1,"principal":100000,"financingDays":7}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("financing days exceed limit"));
    }

    @Test
    @DisplayName("POST /loans/{id}/disburse — IllegalArgumentException 도 400 으로 매핑된다")
    void disburseInvalidArgumentMapsTo400() throws Exception {
        when(disburseLoanUseCase.disburse(anyLong()))
                .thenThrow(new IllegalArgumentException("loan not found"));

        mockMvc.perform(post("/loans/999/disburse"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"));
    }

    private static LoanAdvance loan() {
        return LoanAdvance.reconstitute(1L, 7L, new BigDecimal("800000"),
                new BigDecimal("800"), new BigDecimal("800800"), LoanStatus.DISBURSED);
    }

    @Test
    @DisplayName("POST /loans — 신청 성공은 201 + 대출 본문")
    void requestCreated() throws Exception {
        when(requestLoanUseCase.request(any())).thenReturn(loan());

        mockMvc.perform(post("/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sellerId":7,"principal":800000,"financingDays":7}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.sellerId").value(7))
                .andExpect(jsonPath("$.status").value("DISBURSED"));
    }

    @Test
    @DisplayName("POST /loans/{id}/disburse — 실행 성공은 200")
    void disburseOk() throws Exception {
        when(disburseLoanUseCase.disburse(1L)).thenReturn(loan());

        mockMvc.perform(post("/loans/1/disburse"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    @DisplayName("GET /loans?sellerId= — 셀러별 대출 목록")
    void bySeller() throws Exception {
        when(loadLoanPort.findBySeller(7L)).thenReturn(List.of(loan()));

        mockMvc.perform(get("/loans").param("sellerId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sellerId").value(7));

        verify(loadLoanPort).findBySeller(eq(7L));
    }
}
