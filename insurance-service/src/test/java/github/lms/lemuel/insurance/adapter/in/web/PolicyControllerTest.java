package github.lms.lemuel.insurance.adapter.in.web;

import github.lms.lemuel.insurance.application.port.in.CancelPolicyUseCase;
import github.lms.lemuel.insurance.application.port.in.GetPolicyPayoutsUseCase;
import github.lms.lemuel.insurance.application.port.in.GetPolicyPayoutsUseCase.GeneralPayoutSummary;
import github.lms.lemuel.insurance.application.port.in.PolicyTerminationResult;
import github.lms.lemuel.insurance.application.port.in.SurrenderPolicyUseCase;
import github.lms.lemuel.insurance.domain.GeneralPayoutStatus;
import github.lms.lemuel.insurance.domain.GeneralPayoutType;
import github.lms.lemuel.insurance.domain.PolicyStatus;
import github.lms.lemuel.insurance.domain.exception.InvalidPolicyTransitionException;
import github.lms.lemuel.insurance.domain.exception.PolicyNotFoundException;
import github.lms.lemuel.insurance.domain.exception.PolicyOwnershipException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 해지·철회·지급내역 API 상태코드 계약 테스트 (§14) — standalone MockMvc (컨텍스트 미기동).
 *
 * <p>돈 경로의 거부 경로(404·403·409)가 예외 종류별로 정확히 매핑되는지 고정한다 —
 * 전역 catch-all 이 409 를 500 으로 삼키면 클라이언트가 재시도해선 안 될 요청을 재시도한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PolicyController — 해지·철회·지급내역 API 상태코드 계약")
class PolicyControllerTest {

    private static final String POLICY_NUMBER = "POL-20230101-bbbb2222";
    private static final String FC_BODY = "{\"fcId\":\"fc-100\"}";

    @Mock SurrenderPolicyUseCase surrenderUseCase;
    @Mock CancelPolicyUseCase cancelUseCase;
    @Mock GetPolicyPayoutsUseCase getPayoutsUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new PolicyController(surrenderUseCase, cancelUseCase, getPayoutsUseCase)).build();
    }

    private static GeneralPayoutSummary payoutSummary() {
        return new GeneralPayoutSummary("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                GeneralPayoutType.SURRENDER_REFUND, new BigDecimal("2220000.00"),
                GeneralPayoutStatus.REQUESTED, LocalDate.of(2026, 8, 8), null,
                new BigDecimal("3700000.00"), new BigDecimal("0.6000"), 36, 37);
    }

    @Test
    @DisplayName("해지 성공은 200 + 전이 상태와 환급금 요약을 돌려준다")
    void surrenderReturns200WithPayout() throws Exception {
        when(surrenderUseCase.surrender(any())).thenReturn(new PolicyTerminationResult(
                POLICY_NUMBER, PolicyStatus.SURRENDERED, payoutSummary()));

        mockMvc.perform(post("/api/insurance/policies/{n}/surrender", POLICY_NUMBER)
                        .contentType(MediaType.APPLICATION_JSON).content(FC_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SURRENDERED"))
                .andExpect(jsonPath("$.payout.payoutType").value("SURRENDER_REFUND"))
                .andExpect(jsonPath("$.payout.amount").value(2220000.00));
    }

    @Test
    @DisplayName("환급액 0 해지도 200 — payout 은 null 로 내려간다")
    void surrenderWithoutPayoutReturns200() throws Exception {
        when(surrenderUseCase.surrender(any())).thenReturn(new PolicyTerminationResult(
                POLICY_NUMBER, PolicyStatus.SURRENDERED, null));

        mockMvc.perform(post("/api/insurance/policies/{n}/surrender", POLICY_NUMBER)
                        .contentType(MediaType.APPLICATION_JSON).content(FC_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payout").doesNotExist());
    }

    @Test
    @DisplayName("없는 계약은 404")
    void unknownPolicyReturns404() throws Exception {
        when(surrenderUseCase.surrender(any()))
                .thenThrow(new PolicyNotFoundException(POLICY_NUMBER));

        mockMvc.perform(post("/api/insurance/policies/{n}/surrender", POLICY_NUMBER)
                        .contentType(MediaType.APPLICATION_JSON).content(FC_BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("담당 FC 가 아니면 403")
    void wrongFcReturns403() throws Exception {
        when(surrenderUseCase.surrender(any()))
                .thenThrow(new PolicyOwnershipException(POLICY_NUMBER, "fc-999"));

        mockMvc.perform(post("/api/insurance/policies/{n}/surrender", POLICY_NUMBER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fcId\":\"fc-999\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("이미 종료된 계약 해지는 409 — 500 으로 삼키지 않는다")
    void terminalPolicyReturns409() throws Exception {
        when(surrenderUseCase.surrender(any())).thenThrow(
                new InvalidPolicyTransitionException(PolicyStatus.EXPIRED, PolicyStatus.SURRENDERED));

        mockMvc.perform(post("/api/insurance/policies/{n}/surrender", POLICY_NUMBER)
                        .contentType(MediaType.APPLICATION_JSON).content(FC_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("철회 창구(15일) 초과는 409")
    void cancelPastWindowReturns409() throws Exception {
        when(cancelUseCase.cancel(any())).thenThrow(
                new InvalidPolicyTransitionException("청약철회는 효력일로부터 15일 이내에만 가능합니다"));

        mockMvc.perform(post("/api/insurance/policies/{n}/cancel", POLICY_NUMBER)
                        .contentType(MediaType.APPLICATION_JSON).content(FC_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("fcId 누락 요청은 400 — 검증이 유스케이스 앞에 선다")
    void missingFcIdReturns400() throws Exception {
        mockMvc.perform(post("/api/insurance/policies/{n}/surrender", POLICY_NUMBER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fcId\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("지급 내역 조회는 200 + 산출근거를 포함한다")
    void payoutsReturns200WithBasis() throws Exception {
        when(getPayoutsUseCase.byPolicyNumber(POLICY_NUMBER)).thenReturn(List.of(payoutSummary()));

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
        when(getPayoutsUseCase.byPolicyNumber(POLICY_NUMBER))
                .thenThrow(new PolicyNotFoundException(POLICY_NUMBER));

        mockMvc.perform(get("/api/insurance/policies/{n}/payouts", POLICY_NUMBER))
                .andExpect(status().isNotFound());
    }
}
