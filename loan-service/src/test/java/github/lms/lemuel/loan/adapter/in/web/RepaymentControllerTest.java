package github.lms.lemuel.loan.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.loan.application.port.in.SimulateRepaymentUseCase;
import github.lms.lemuel.loan.application.port.in.SimulateRepaymentUseCase.SimulateRepaymentCommand;
import github.lms.lemuel.loan.domain.RepaymentMethod;
import github.lms.lemuel.loan.domain.RepaymentSchedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 상환 시뮬레이션 API 웹 계층 가드 — 정상 200 매핑 + 요청 형식 검증(@Valid) 400.
 */
@WebMvcTest(controllers = RepaymentController.class)
@AutoConfigureMockMvc(addFilters = false)
class RepaymentControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean SimulateRepaymentUseCase simulateRepaymentUseCase;

    @Test
    @DisplayName("POST /loans/repayment/simulate — 만기일시 스케줄을 200 으로 반환한다")
    void simulateReturnsSchedule() throws Exception {
        when(simulateRepaymentUseCase.simulate(any(SimulateRepaymentCommand.class)))
                .thenReturn(RepaymentSchedule.of(
                        new BigDecimal("1200000"), 12, new BigDecimal("6.0"), RepaymentMethod.BULLET));

        mockMvc.perform(post("/loans/repayment/simulate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"principal":1200000,"termMonths":12,"annualRatePercent":6.0,"method":"BULLET"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("BULLET"))
                .andExpect(jsonPath("$.methodLabel").value("만기일시상환"))
                .andExpect(jsonPath("$.totalInterest").value(72000))
                .andExpect(jsonPath("$.totalPayment").value(1272000))
                .andExpect(jsonPath("$.installments.length()").value(12))
                .andExpect(jsonPath("$.installments[11].payment").value(1206000));
    }

    @Test
    @DisplayName("POST /loans/repayment/simulate — 원금 음수는 형식검증 400")
    void negativePrincipalIsRejected() throws Exception {
        mockMvc.perform(post("/loans/repayment/simulate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"principal":-1,"termMonths":12,"annualRatePercent":6.0,"method":"BULLET"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /loans/repayment/simulate — 상환방식 누락은 형식검증 400")
    void missingMethodIsRejected() throws Exception {
        mockMvc.perform(post("/loans/repayment/simulate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"principal":1200000,"termMonths":12,"annualRatePercent":6.0}
                                """))
                .andExpect(status().isBadRequest());
    }
}
