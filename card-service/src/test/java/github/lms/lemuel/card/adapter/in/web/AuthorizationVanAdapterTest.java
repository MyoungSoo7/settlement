package github.lms.lemuel.card.adapter.in.web;

import github.lms.lemuel.card.application.port.in.AuthorizeCardUseCase;
import github.lms.lemuel.card.application.port.in.AuthorizeCardUseCase.AuthorizationResult;
import github.lms.lemuel.card.application.port.in.AuthorizeCardUseCase.AuthorizeCardCommand;
import github.lms.lemuel.card.domain.AuthorizationHold;
import github.lms.lemuel.card.domain.DeclineReason;
import github.lms.lemuel.card.domain.HoldStatus;
import github.lms.lemuel.common.config.jwt.JwtUtil;
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
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * VAN 시뮬레이터 어댑터 단위 테스트.
 *
 * <p>검증 대상: HTTP 변환(요청 매핑 → UseCase 커맨드 → 응답 매핑).
 * 승인 로직 자체는 AuthorizeCardService 단위 테스트와 ConcurrentAuthorizationTest 가 검증한다.
 */
@WebMvcTest(controllers = AuthorizationVanAdapter.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(github.lms.lemuel.common.config.CacheConfig.class)
class AuthorizationVanAdapterTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JwtUtil jwtUtil;

    @MockitoBean
    AuthorizeCardUseCase authorizeCardUseCase;

    @Test
    @DisplayName("승인 요청 → 200 approved=true + authorizationCode 반환")
    void authorize_approved_returns200WithAuthCode() throws Exception {
        AuthorizationHold hold = AuthorizationHold.builder()
                .id(1L)
                .authorizationId("AUTH-VAN-0001")
                .cardId(9001L)
                .cardAccountId(5001L)
                .holderUserId(888L)
                .amount(new BigDecimal("45000"))
                .status(HoldStatus.ACTIVE)
                .merchantName("스타벅스 강남점")
                .mcc("5812")
                .authorizedAt(Instant.parse("2026-08-02T10:15:30Z"))
                .build();

        when(authorizeCardUseCase.authorize(any(AuthorizeCardCommand.class)))
                .thenReturn(AuthorizationResult.approved(hold));

        mockMvc.perform(post("/van/v1/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "networkRequestId": "AUTH-VAN-0001",
                                  "cardId": 9001,
                                  "amount": 45000.00,
                                  "merchantName": "스타벅스 강남점",
                                  "mcc": "5812",
                                  "overseas": false,
                                  "online": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(true))
                .andExpect(jsonPath("$.authorizationCode").value("AUTH-VAN-0001"))
                .andExpect(jsonPath("$.declineCode").isEmpty());
    }

    @Test
    @DisplayName("거절 요청(한도초과) → 200 approved=false + LIMIT_EXCEEDED")
    void authorize_declined_limitExceeded_returns200WithDeclineCode() throws Exception {
        when(authorizeCardUseCase.authorize(any(AuthorizeCardCommand.class)))
                .thenReturn(AuthorizationResult.declined(DeclineReason.LIMIT_EXCEEDED));

        mockMvc.perform(post("/van/v1/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "networkRequestId": "AUTH-VAN-0002",
                                  "cardId": 9001,
                                  "amount": 9999999.00,
                                  "overseas": false,
                                  "online": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(false))
                .andExpect(jsonPath("$.declineCode").value("LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.authorizationCode").isEmpty());
    }

    @Test
    @DisplayName("거절 요청(카드정지) → 200 CARD_SUSPENDED")
    void authorize_declined_cardSuspended() throws Exception {
        when(authorizeCardUseCase.authorize(any(AuthorizeCardCommand.class)))
                .thenReturn(AuthorizationResult.declined(DeclineReason.CARD_SUSPENDED));

        mockMvc.perform(post("/van/v1/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "networkRequestId": "AUTH-VAN-0003",
                                  "cardId": 9001,
                                  "amount": 1000.00,
                                  "overseas": false,
                                  "online": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(false))
                .andExpect(jsonPath("$.declineCode").value("CARD_SUSPENDED"));
    }

    @Test
    @DisplayName("거절 요청(이탈멤버) → 200 MEMBER_INACTIVE")
    void authorize_declined_memberInactive() throws Exception {
        when(authorizeCardUseCase.authorize(any(AuthorizeCardCommand.class)))
                .thenReturn(AuthorizationResult.declined(DeclineReason.MEMBER_INACTIVE));

        mockMvc.perform(post("/van/v1/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "networkRequestId": "AUTH-VAN-0004",
                                  "cardId": 9001,
                                  "amount": 1000.00,
                                  "overseas": false,
                                  "online": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(false))
                .andExpect(jsonPath("$.declineCode").value("MEMBER_INACTIVE"));
    }

    @Test
    @DisplayName("거절 요청(가맹점정책 위반) → 200 MERCHANT_POLICY_VIOLATION")
    void authorize_declined_merchantPolicyViolation() throws Exception {
        when(authorizeCardUseCase.authorize(any(AuthorizeCardCommand.class)))
                .thenReturn(AuthorizationResult.declined(DeclineReason.MERCHANT_POLICY_VIOLATION));

        mockMvc.perform(post("/van/v1/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "networkRequestId": "AUTH-VAN-0005",
                                  "cardId": 9001,
                                  "amount": 1000.00,
                                  "mcc": "5813",
                                  "overseas": false,
                                  "online": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(false))
                .andExpect(jsonPath("$.declineCode").value("MERCHANT_POLICY_VIOLATION"));
    }

    @Test
    @DisplayName("필수 필드 누락(networkRequestId 없음) → 400")
    void authorize_missingNetworkRequestId_returns400() throws Exception {
        mockMvc.perform(post("/van/v1/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cardId": 9001,
                                  "amount": 1000.00,
                                  "overseas": false,
                                  "online": false
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("금액 0 이하 → 400")
    void authorize_zeroAmount_returns400() throws Exception {
        mockMvc.perform(post("/van/v1/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "networkRequestId": "AUTH-VAN-0006",
                                  "cardId": 9001,
                                  "amount": 0,
                                  "overseas": false,
                                  "online": false
                                }
                                """))
                .andExpect(status().isBadRequest());
    }
}
