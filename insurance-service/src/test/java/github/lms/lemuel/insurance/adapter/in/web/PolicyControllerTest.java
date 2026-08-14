package github.lms.lemuel.insurance.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.insurance.application.port.in.CancelPolicyUseCase;
import github.lms.lemuel.insurance.application.port.in.CancelPolicyUseCase.CancelPolicyCommand;
import github.lms.lemuel.insurance.application.port.in.GetPolicyPayoutsUseCase;
import github.lms.lemuel.insurance.application.port.in.GetPolicyPayoutsUseCase.GeneralPayoutSummary;
import github.lms.lemuel.insurance.application.port.in.PolicyTerminationResult;
import github.lms.lemuel.insurance.application.port.in.SurrenderPolicyUseCase;
import github.lms.lemuel.insurance.application.port.in.SurrenderPolicyUseCase.SurrenderPolicyCommand;
import github.lms.lemuel.insurance.domain.GeneralPayoutStatus;
import github.lms.lemuel.insurance.domain.GeneralPayoutType;
import github.lms.lemuel.insurance.domain.PolicyStatus;
import github.lms.lemuel.insurance.domain.exception.InvalidPolicyTransitionException;
import github.lms.lemuel.insurance.domain.exception.PolicyNotFoundException;
import github.lms.lemuel.insurance.domain.exception.PolicyOwnershipException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 해지·철회·지급내역 API 계약 테스트 (§14) — 상태코드 + <b>FC 식별자가 JWT 주체에서만 온다</b>는
 * 불변식.
 *
 * <p>해지·철회는 계약을 끝내고 환급금을 발생시키는 돈 경로다 — 본문 fcId 를 신뢰하면 남의
 * 식별자를 아는 것만으로 타인 계약을 해지시킬 수 있다. 그 통로가 없음을 여기서 고정한다.
 *
 * <p>standaloneSetup 은 시큐리티 필터를 태우지 않으므로 {@link SecurityContextHolder} 를 직접 세팅한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PolicyController — API 계약 + JWT 소유권")
class PolicyControllerTest {

    private static final String POLICY_NUMBER = "POL-20230101-bbbb2222";
    /** JWT userId 100 → FC 식별자 "100" (FcIdentity 파생 규칙). */
    private static final long JWT_USER_ID = 100L;
    private static final String DERIVED_FC_ID = "100";

    @Mock SurrenderPolicyUseCase surrenderUseCase;
    @Mock CancelPolicyUseCase cancelUseCase;
    @Mock GetPolicyPayoutsUseCase getPayoutsUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new PolicyController(surrenderUseCase, cancelUseCase, getPayoutsUseCase)).build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** JWT 인증 주체를 SecurityContext 에 세팅한다 (userId 없는 구 토큰은 uid=null). */
    private static void authenticateAs(Long userId) {
        AuthPrincipal principal = new AuthPrincipal(userId, "fc@example.com", "USER");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private static GeneralPayoutSummary payoutSummary() {
        return new GeneralPayoutSummary("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                GeneralPayoutType.SURRENDER_REFUND, new BigDecimal("2220000.00"),
                GeneralPayoutStatus.REQUESTED, LocalDate.of(2026, 8, 8), null,
                new BigDecimal("3700000.00"), new BigDecimal("0.6000"), 36, 37);
    }

    // ────────────────────────────────────────────────────────────────────
    // IDOR — FC 식별자는 JWT 에서만
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("해지의 FC 식별자는 JWT 주체에서 파생된다 — 본문 fcId 는 무시된다")
    void surrenderDerivesFcIdFromJwtNotBody() throws Exception {
        authenticateAs(JWT_USER_ID);
        when(surrenderUseCase.surrender(any())).thenReturn(new PolicyTerminationResult(
                POLICY_NUMBER, PolicyStatus.SURRENDERED, payoutSummary()));

        // 공격자가 남의 fcId 를 본문에 실어도 바인딩될 통로가 없다
        mockMvc.perform(post("/api/insurance/policies/{n}/surrender", POLICY_NUMBER)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"fcId\":\"victim-fc\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<SurrenderPolicyCommand> cmd =
                ArgumentCaptor.forClass(SurrenderPolicyCommand.class);
        verify(surrenderUseCase).surrender(cmd.capture());
        assertThat(cmd.getValue().fcId()).isEqualTo(DERIVED_FC_ID);
    }

    @Test
    @DisplayName("철회의 FC 식별자도 JWT 주체에서 파생된다")
    void cancelDerivesFcIdFromJwt() throws Exception {
        authenticateAs(JWT_USER_ID);
        when(cancelUseCase.cancel(any())).thenReturn(new PolicyTerminationResult(
                POLICY_NUMBER, PolicyStatus.CANCELLED, payoutSummary()));

        mockMvc.perform(post("/api/insurance/policies/{n}/cancel", POLICY_NUMBER))
                .andExpect(status().isOk());

        ArgumentCaptor<CancelPolicyCommand> cmd = ArgumentCaptor.forClass(CancelPolicyCommand.class);
        verify(cancelUseCase).cancel(cmd.capture());
        assertThat(cmd.getValue().fcId()).isEqualTo(DERIVED_FC_ID);
    }

    @Test
    @DisplayName("userId 없는 구 토큰의 해지는 403 — 유스케이스에 닿지 않는다")
    void legacyTokenSurrenderReturns403() throws Exception {
        authenticateAs(null);

        mockMvc.perform(post("/api/insurance/policies/{n}/surrender", POLICY_NUMBER))
                .andExpect(status().isForbidden());

        verify(surrenderUseCase, never()).surrender(any());
    }

    @Test
    @DisplayName("인증 주체가 없으면 403 — 계약 존재 여부가 새 나가지 않는다")
    void unauthenticatedPayoutsReturns403() throws Exception {
        mockMvc.perform(get("/api/insurance/policies/{n}/payouts", POLICY_NUMBER))
                .andExpect(status().isForbidden());

        verify(getPayoutsUseCase, never()).byPolicyNumber(any(), any());
    }

    // ────────────────────────────────────────────────────────────────────
    // 상태코드 계약
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("해지 성공은 200 + 전이 상태와 환급금 요약을 돌려준다")
    void surrenderReturns200WithPayout() throws Exception {
        authenticateAs(JWT_USER_ID);
        when(surrenderUseCase.surrender(any())).thenReturn(new PolicyTerminationResult(
                POLICY_NUMBER, PolicyStatus.SURRENDERED, payoutSummary()));

        mockMvc.perform(post("/api/insurance/policies/{n}/surrender", POLICY_NUMBER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SURRENDERED"))
                .andExpect(jsonPath("$.payout.payoutType").value("SURRENDER_REFUND"))
                .andExpect(jsonPath("$.payout.amount").value(2220000.00));
    }

    @Test
    @DisplayName("환급액 0 해지도 200 — payout 은 null 로 내려간다")
    void surrenderWithoutPayoutReturns200() throws Exception {
        authenticateAs(JWT_USER_ID);
        when(surrenderUseCase.surrender(any())).thenReturn(new PolicyTerminationResult(
                POLICY_NUMBER, PolicyStatus.SURRENDERED, null));

        mockMvc.perform(post("/api/insurance/policies/{n}/surrender", POLICY_NUMBER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payout").doesNotExist());
    }

    @Test
    @DisplayName("없는 계약은 404")
    void unknownPolicyReturns404() throws Exception {
        authenticateAs(JWT_USER_ID);
        when(surrenderUseCase.surrender(any()))
                .thenThrow(new PolicyNotFoundException(POLICY_NUMBER));

        mockMvc.perform(post("/api/insurance/policies/{n}/surrender", POLICY_NUMBER))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("담당 FC 가 아니면 403")
    void wrongFcReturns403() throws Exception {
        authenticateAs(999L);
        when(surrenderUseCase.surrender(any()))
                .thenThrow(new PolicyOwnershipException(POLICY_NUMBER, "999"));

        mockMvc.perform(post("/api/insurance/policies/{n}/surrender", POLICY_NUMBER))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("이미 종료된 계약 해지는 409 — 500 으로 삼키지 않는다")
    void terminalPolicyReturns409() throws Exception {
        authenticateAs(JWT_USER_ID);
        when(surrenderUseCase.surrender(any())).thenThrow(
                new InvalidPolicyTransitionException(PolicyStatus.EXPIRED, PolicyStatus.SURRENDERED));

        mockMvc.perform(post("/api/insurance/policies/{n}/surrender", POLICY_NUMBER))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("철회 창구(15일) 초과는 409")
    void cancelPastWindowReturns409() throws Exception {
        authenticateAs(JWT_USER_ID);
        when(cancelUseCase.cancel(any())).thenThrow(
                new InvalidPolicyTransitionException("청약철회는 효력일로부터 15일 이내에만 가능합니다"));

        mockMvc.perform(post("/api/insurance/policies/{n}/cancel", POLICY_NUMBER))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("지급 내역 조회는 200 + 산출근거를 포함하고 JWT fcId 로 소유권을 대조한다")
    void payoutsReturns200WithBasis() throws Exception {
        authenticateAs(JWT_USER_ID);
        when(getPayoutsUseCase.byPolicyNumber(eq(POLICY_NUMBER), eq(DERIVED_FC_ID)))
                .thenReturn(List.of(payoutSummary()));

        mockMvc.perform(get("/api/insurance/policies/{n}/payouts", POLICY_NUMBER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].paidPremiumTotal").value(3700000.00))
                .andExpect(jsonPath("$[0].appliedRate").value(0.6000))
                .andExpect(jsonPath("$[0].installmentCount").value(37))
                .andExpect(jsonPath("$[0].status").value("REQUESTED"));
    }

    @Test
    @DisplayName("없는 계약의 지급 내역 조회는 404")
    void payoutsUnknownPolicyReturns404() throws Exception {
        authenticateAs(JWT_USER_ID);
        when(getPayoutsUseCase.byPolicyNumber(eq(POLICY_NUMBER), any()))
                .thenThrow(new PolicyNotFoundException(POLICY_NUMBER));

        mockMvc.perform(get("/api/insurance/policies/{n}/payouts", POLICY_NUMBER))
                .andExpect(status().isNotFound());
    }
}
