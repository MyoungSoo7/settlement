package github.lms.lemuel.settlement.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtAuthenticationFilter;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.common.config.jwt.SecurityConfig;
import github.lms.lemuel.settlement.application.port.in.GenerateSettlementPdfUseCase;
import github.lms.lemuel.settlement.application.port.in.GetSettlementUseCase;
import github.lms.lemuel.settlement.domain.Settlement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SecurityConfig 의 /settlements/** hasAnyRole("ADMIN","MANAGER") 규칙을
 * 실제 필터체인으로 3단계(401/403/200) 검증한다.
 */
@WebMvcTest(controllers = SettlementController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
@ActiveProfiles("test")
class SettlementControllerSecurityTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean GetSettlementUseCase getSettlementUseCase;
    @MockitoBean GenerateSettlementPdfUseCase generateSettlementPdfUseCase;
    @MockitoBean JwtUtil jwtUtil;

    @Test
    @DisplayName("미인증 요청은 401 — authenticationEntryPoint")
    void anonymousReturns401() throws Exception {
        mockMvc.perform(get("/settlements/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("USER 권한은 403 — accessDeniedHandler (정산은 ADMIN/MANAGER 전용)")
    @WithMockUser(roles = "USER")
    void userRoleReturns403() throws Exception {
        mockMvc.perform(get("/settlements/1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN 권한은 200 — 필터 통과 후 컨트롤러 도달")
    @WithMockUser(roles = "ADMIN")
    void adminRoleReturns200() throws Exception {
        Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("50000"), LocalDate.now());
        when(getSettlementUseCase.getSettlementById(1L)).thenReturn(s);

        mockMvc.perform(get("/settlements/1"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("MANAGER 권한도 200 — hasAnyRole 에 포함")
    @WithMockUser(roles = "MANAGER")
    void managerRoleReturns200() throws Exception {
        Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("50000"), LocalDate.now());
        when(getSettlementUseCase.getSettlementById(1L)).thenReturn(s);

        mockMvc.perform(get("/settlements/1"))
                .andExpect(status().isOk());
    }
}
