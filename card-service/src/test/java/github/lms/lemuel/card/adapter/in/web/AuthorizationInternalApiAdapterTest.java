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
 * 내부 API 어댑터 단위 테스트.
 *
 * <p>검증 대상:
 * <ul>
 *   <li>경로 변수 cardId 와 본문 cardId 일치 검증(불일치 → 400)</li>
 *   <li>HTTP 변환(요청 매핑 → UseCase 커맨드 → 응답 매핑)</li>
 *   <li>승인/거절 응답 필드</li>
 * </ul>
 */
@WebMvcTest(controllers = AuthorizationInternalApiAdapter.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(github.lms.lemuel.common.config.CacheConfig.class)
class AuthorizationInternalApiAdapterTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JwtUtil jwtUtil;

    @MockitoBean
    AuthorizeCardUseCase authorizeCardUseCase;

    @Test
    @DisplayName("승인 요청 → 200 approved=true + authorizationId·approvedAmount·authorizedAt 반환")
    void authorize_approved_returns200() throws Exception {
        Instant now = Instant.parse("2026-08-02T11:00:00Z");
        AuthorizationHold hold = AuthorizationHold.builder()
                .id(10L)
                .authorizationId("INT-AUTH-0001")
                .cardId(9001L)
                .cardAccountId(5001L)
                .holderUserId(777L)
                .amount(new BigDecimal("120000"))
                .status(HoldStatus.ACTIVE)
                .merchantName("쿠팡")
                .mcc("5961")
                .authorizedAt(now)
                .build();

        when(authorizeCardUseCase.authorize(any(AuthorizeCardCommand.class)))
                .thenReturn(AuthorizationResult.approved(hold));

        mockMvc.perform(post("/internal/api/v1/cards/9001/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "authorizationId": "INT-AUTH-0001",
                                  "cardId": 9001,
                                  "amount": 120000.00,
                                  "merchantName": "쿠팡",
                                  "mcc": "5961",
                                  "overseas": false,
                                  "online": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(true))
                .andExpect(jsonPath("$.authorizationId").value("INT-AUTH-0001"))
                .andExpect(jsonPath("$.approvedAmount").value(120000))
                .andExpect(jsonPath("$.declineReason").isEmpty());
    }

    @Test
    @DisplayName("거절 요청 → 200 approved=false + declineReason 반환")
    void authorize_declined_returns200WithDeclineReason() throws Exception {
        when(authorizeCardUseCase.authorize(any(AuthorizeCardCommand.class)))
                .thenReturn(AuthorizationResult.declined(DeclineReason.LIMIT_EXCEEDED));

        mockMvc.perform(post("/internal/api/v1/cards/9001/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "authorizationId": "INT-AUTH-0002",
                                  "cardId": 9001,
                                  "amount": 9999999.00,
                                  "overseas": false,
                                  "online": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(false))
                .andExpect(jsonPath("$.declineReason").value("LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.authorizationId").isEmpty());
    }

    @Test
    @DisplayName("경로 cardId 와 본문 cardId 불일치 → 400")
    void authorize_cardIdMismatch_returns400() throws Exception {
        mockMvc.perform(post("/internal/api/v1/cards/9001/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "authorizationId": "INT-AUTH-0003",
                                  "cardId": 9999,
                                  "amount": 10000.00,
                                  "overseas": false,
                                  "online": false
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("필수 필드 누락(authorizationId 없음) → 400")
    void authorize_missingAuthorizationId_returns400() throws Exception {
        mockMvc.perform(post("/internal/api/v1/cards/9001/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cardId": 9001,
                                  "amount": 10000.00,
                                  "overseas": false,
                                  "online": false
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("금액 0 이하 → 400")
    void authorize_zeroAmount_returns400() throws Exception {
        mockMvc.perform(post("/internal/api/v1/cards/9001/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "authorizationId": "INT-AUTH-0004",
                                  "cardId": 9001,
                                  "amount": 0,
                                  "overseas": false,
                                  "online": false
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("거절 사유 — MERCHANT_POLICY_VIOLATION 정상 직렬화")
    void authorize_merchantPolicyViolation_returns200WithDeclineReason() throws Exception {
        when(authorizeCardUseCase.authorize(any(AuthorizeCardCommand.class)))
                .thenReturn(AuthorizationResult.declined(DeclineReason.MERCHANT_POLICY_VIOLATION));

        mockMvc.perform(post("/internal/api/v1/cards/9001/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "authorizationId": "INT-AUTH-0005",
                                  "cardId": 9001,
                                  "amount": 1000.00,
                                  "mcc": "9999",
                                  "overseas": false,
                                  "online": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(false))
                .andExpect(jsonPath("$.declineReason").value("MERCHANT_POLICY_VIOLATION"));
    }
}
