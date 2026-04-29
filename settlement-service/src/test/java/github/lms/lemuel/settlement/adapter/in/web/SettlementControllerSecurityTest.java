package github.lms.lemuel.settlement.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.common.config.jwt.SecurityConfig;
import github.lms.lemuel.settlement.application.port.in.GenerateSettlementPdfUseCase;
import github.lms.lemuel.settlement.application.port.in.GetSettlementUseCase;
import github.lms.lemuel.settlement.domain.Settlement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SecurityConfig 의 /settlements/** hasAnyRole("ADMIN","MANAGER") 규칙을
 * 실제 필터체인으로 3단계(401/403/200) 검증한다.
 *
 * <p>Spring Security 6 + STATELESS 세션 정책에서는 {@code @WithMockUser} 가
 * SecurityContextHolderFilter 에 의해 덮어씌워지므로, MockMvc request post-processor
 * ({@code .with(user(...))}) 방식을 사용한다.</p>
 */
@WebMvcTest(controllers = SettlementController.class)
@Import({SecurityConfig.class})
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
    void userRoleReturns403() throws Exception {
        mockMvc.perform(get("/settlements/1")
                        .with(user("user@test.com").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN 권한은 200 — 필터 통과 후 컨트롤러 도달")
    void adminRoleReturns200() throws Exception {
        Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("50000"), LocalDate.now());
        when(getSettlementUseCase.getSettlementById(1L)).thenReturn(s);

        mockMvc.perform(get("/settlements/1")
                        .with(user("admin@test.com").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("MANAGER 권한도 200 — hasAnyRole 에 포함")
    void managerRoleReturns200() throws Exception {
        Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("50000"), LocalDate.now());
        when(getSettlementUseCase.getSettlementById(1L)).thenReturn(s);

        mockMvc.perform(get("/settlements/1")
                        .with(user("manager@test.com").roles("MANAGER")))
                .andExpect(status().isOk());
    }
}
