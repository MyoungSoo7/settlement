package github.lms.lemuel.payout.adapter.in.web;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.common.config.JacksonCompatConfig;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.common.exception.GlobalExceptionHandler;
import github.lms.lemuel.payout.application.port.in.RegisterSellerBankAccountUseCase;
import github.lms.lemuel.payout.application.port.out.LoadSellerBankAccountRegistrationPort;
import github.lms.lemuel.payout.domain.SellerBankAccountRegistration;
import github.lms.lemuel.payout.domain.exception.PayoutInvariantViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 셀러 셀프서비스 지급 계좌 API 계약 검증.
 *
 * <p>핵심은 IDOR 차단 — 셀러 식별자는 요청이 아니라 JWT 주체({@link AuthPrincipal#userId()})에서만
 * 파생된다. 요청 본문에 sellerId 류 필드가 실려 와도 무시되어야 한다.
 */
@WebMvcTest(controllers = SellerBankAccountSelfController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({JacksonCompatConfig.class, GlobalExceptionHandler.class})
class SellerBankAccountSelfControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean RegisterSellerBankAccountUseCase registerUseCase;
    @MockitoBean LoadSellerBankAccountRegistrationPort loadPort;
    @MockitoBean AuditLogger auditLogger;

    /**
     * addFilters=false 라 JWT 필터가 SecurityContext 를 채우지 않으므로, 운영에서 필터가 채우는
     * 지점({@code SecurityContextHolder})에 직접 인증 주체를 넣는다 (컨트롤러도 같은 지점을 읽는다).
     */
    private static void loginAs(Long userId) {
        AuthPrincipal principal = new AuthPrincipal(userId, "seller@lemuel.dev", "USER");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static SellerBankAccountRegistration registration(Long sellerId) {
        return SellerBankAccountRegistration.rehydrate(sellerId, "KB", "1234567890", "홍길동",
                LocalDateTime.of(2026, 8, 1, 12, 0));
    }

    @Test
    @DisplayName("PUT /api/seller/bank-account — JWT 주체(userId)로 본인 계좌 upsert, 마스킹 응답")
    void register_derivesSellerIdFromJwt() throws Exception {
        when(registerUseCase.register(10L, "KB", "1234567890", "홍길동")).thenReturn(registration(10L));

        loginAs(10L);

        mockMvc.perform(put("/api/seller/bank-account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankCode\":\"KB\",\"accountNumber\":\"1234567890\",\"accountHolder\":\"홍길동\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerId").value(10))
                .andExpect(jsonPath("$.bank").value("KB"))
                .andExpect(jsonPath("$.account").value("****7890"))
                .andExpect(jsonPath("$.holder").value("홍길동"));

        verify(registerUseCase).register(10L, "KB", "1234567890", "홍길동");
    }

    @Test
    @DisplayName("PUT — 본문에 sellerId 를 실어 보내도 무시된다 (IDOR 차단)")
    void register_ignoresSellerIdInBody() throws Exception {
        when(registerUseCase.register(10L, "KB", "1234567890", "홍길동")).thenReturn(registration(10L));

        loginAs(10L);

        mockMvc.perform(put("/api/seller/bank-account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sellerId\":999,\"bankCode\":\"KB\"," +
                                "\"accountNumber\":\"1234567890\",\"accountHolder\":\"홍길동\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerId").value(10));

        verify(registerUseCase).register(eq(10L), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("PUT — 계좌 변경은 감사로그에 마스킹 계좌만 남긴다")
    void register_auditsWithMaskedAccount() throws Exception {
        when(registerUseCase.register(10L, "KB", "1234567890", "홍길동")).thenReturn(registration(10L));

        loginAs(10L);

        mockMvc.perform(put("/api/seller/bank-account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankCode\":\"KB\",\"accountNumber\":\"1234567890\",\"accountHolder\":\"홍길동\"}"))
                .andExpect(status().isOk());

        // 감사 페이로드에 계좌 원문(1234567890)이 아니라 마스킹(****7890)만 실리고,
        // 셀프서비스 경로임이 channel=SELF 로 구분돼야 한다(관리자 대행은 ADMIN_CONSOLE)
        verify(auditLogger).record(eq(AuditAction.SELLER_BANK_ACCOUNT_REGISTERED),
                eq("SellerBankAccount"), eq("10"),
                argThat(payload -> payload.contains("****7890")
                        && payload.contains("\"channel\":\"SELF\"")
                        && !payload.contains("1234567890")));
    }

    @Test
    @DisplayName("PUT — userId 없는 구(舊) 토큰은 403, 유스케이스 미호출")
    void register_withoutUserId_forbidden() throws Exception {
        loginAs(null);

        mockMvc.perform(put("/api/seller/bank-account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankCode\":\"KB\",\"accountNumber\":\"1234567890\",\"accountHolder\":\"홍길동\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(registerUseCase);
    }

    @Test
    @DisplayName("PUT — 도메인 검증 실패(빈 bankCode)는 400")
    void register_domainValidationFailure_badRequest() throws Exception {
        when(registerUseCase.register(eq(10L), anyString(), anyString(), anyString()))
                .thenThrow(new PayoutInvariantViolationException("bankCode 는 필수입니다"));

        loginAs(10L);

        mockMvc.perform(put("/api/seller/bank-account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankCode\":\" \",\"accountNumber\":\"1234567890\",\"accountHolder\":\"홍길동\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/seller/bank-account — 본인 계좌 조회(마스킹)")
    void get_ownAccount_masked() throws Exception {
        when(loadPort.findBySellerId(10L)).thenReturn(Optional.of(registration(10L)));

        loginAs(10L);

        mockMvc.perform(get("/api/seller/bank-account"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerId").value(10))
                .andExpect(jsonPath("$.account").value("****7890"));
    }

    @Test
    @DisplayName("GET — 미등록 셀러는 404")
    void get_notRegistered_notFound() throws Exception {
        when(loadPort.findBySellerId(10L)).thenReturn(Optional.empty());

        loginAs(10L);

        mockMvc.perform(get("/api/seller/bank-account"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET — userId 없는 구(舊) 토큰은 403")
    void get_withoutUserId_forbidden() throws Exception {
        loginAs(null);

        mockMvc.perform(get("/api/seller/bank-account"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(loadPort);
    }
}
