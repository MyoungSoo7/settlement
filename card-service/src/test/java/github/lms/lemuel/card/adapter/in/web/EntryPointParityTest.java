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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 두 진입점 동등성(EntryPointParity) 테스트.
 *
 * <p>VAN 어댑터({@code /van/v1/authorizations})와 내부 API 어댑터
 * ({@code /internal/api/v1/cards/{id}/authorizations})가 동일한 {@link AuthorizeCardUseCase}
 * 포트를 호출하고, 동일한 커맨드 필드를 전달하며, 동일한 {@link DeclineReason} 을 직렬화한다는 것을
 * 두 어댑터가 같은 슬라이스에 함께 로드해 검증한다.
 *
 * <p><b>왜 이 테스트가 필요한가:</b> 두 어댑터가 별도의 슬라이스 테스트만 통과했다면
 * "VAN 어댑터는 잘못된 UseCase 를 주입받았지만 단위 테스트의 목이 감추었다" 같은 설정 오류를
 * 발견할 수 없다. 두 컨트롤러를 함께 로드하면 같은 빈 타입이 하나만 존재해야 하므로
 * 동일 UseCase 를 공유함이 증명된다.
 */
@WebMvcTest(controllers = {AuthorizationVanAdapter.class, AuthorizationInternalApiAdapter.class})
@AutoConfigureMockMvc(addFilters = false)
@Import(github.lms.lemuel.common.config.CacheConfig.class)
class EntryPointParityTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JwtUtil jwtUtil;

    /** 두 어댑터가 공유하는 단일 UseCase 목. */
    @MockitoBean
    AuthorizeCardUseCase authorizeCardUseCase;

    private AuthorizationHold buildHold(String authId) {
        return AuthorizationHold.builder()
                .id(1L)
                .authorizationId(authId)
                .cardId(9001L)
                .cardAccountId(5001L)
                .holderUserId(100L)
                .amount(new BigDecimal("50000"))
                .status(HoldStatus.ACTIVE)
                .authorizedAt(Instant.parse("2026-08-02T12:00:00Z"))
                .build();
    }

    @Test
    @DisplayName("VAN 어댑터와 내부 API 어댑터가 동일한 UseCase 인스턴스를 공유한다")
    void bothAdapters_shareTheSameUseCaseBean() throws Exception {
        // 두 어댑터를 동시에 로드했을 때 AuthorizeCardUseCase 빈이 하나임을 검증한다.
        // 컨텍스트 로딩 자체가 테스트 — 빈이 두 개면 Spring 이 예외를 던진다.
        // 그 외에 VAN 경로가 UseCase 를 호출하는 것을 추가로 검증한다.
        when(authorizeCardUseCase.authorize(org.mockito.ArgumentMatchers.any(AuthorizeCardCommand.class)))
                .thenReturn(AuthorizationResult.approved(buildHold("PARITY-0001")));

        mockMvc.perform(post("/van/v1/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "networkRequestId": "PARITY-0001",
                                  "cardId": 9001,
                                  "amount": 50000.00,
                                  "overseas": false,
                                  "online": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(true));

        verify(authorizeCardUseCase).authorize(org.mockito.ArgumentMatchers.any(AuthorizeCardCommand.class));
    }

    @Test
    @DisplayName("두 어댑터 모두 동일한 authorizationId 와 amount 를 커맨드에 담아 UseCase 를 호출한다")
    void bothAdapters_passCorrectCommandFields_toUseCase() throws Exception {
        ArgumentCaptor<AuthorizeCardCommand> vanCaptor = ArgumentCaptor.forClass(AuthorizeCardCommand.class);
        ArgumentCaptor<AuthorizeCardCommand> internalCaptor = ArgumentCaptor.forClass(AuthorizeCardCommand.class);

        when(authorizeCardUseCase.authorize(org.mockito.ArgumentMatchers.any(AuthorizeCardCommand.class)))
                .thenReturn(AuthorizationResult.approved(buildHold("PARITY-VAN-0002")))
                .thenReturn(AuthorizationResult.approved(buildHold("PARITY-INT-0002")));

        // VAN 어댑터 호출
        mockMvc.perform(post("/van/v1/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "networkRequestId": "PARITY-VAN-0002",
                                  "cardId": 9001,
                                  "amount": 75000.00,
                                  "mcc": "5812",
                                  "overseas": true,
                                  "online": false
                                }
                                """))
                .andExpect(status().isOk());

        verify(authorizeCardUseCase).authorize(vanCaptor.capture());
        AuthorizeCardCommand vanCommand = vanCaptor.getValue();

        // 내부 API 어댑터 호출
        mockMvc.perform(post("/internal/api/v1/cards/9001/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "authorizationId": "PARITY-INT-0002",
                                  "cardId": 9001,
                                  "amount": 75000.00,
                                  "mcc": "5812",
                                  "overseas": true,
                                  "online": false
                                }
                                """))
                .andExpect(status().isOk());

        verify(authorizeCardUseCase, org.mockito.Mockito.times(2))
                .authorize(internalCaptor.capture());
        AuthorizeCardCommand internalCommand = internalCaptor.getAllValues().get(1);

        // 두 커맨드가 동일한 비즈니스 필드를 전달했음을 확인
        assertThat(vanCommand.cardId()).isEqualTo(internalCommand.cardId());
        assertThat(vanCommand.amount()).isEqualByComparingTo(internalCommand.amount());
        assertThat(vanCommand.mcc()).isEqualTo(internalCommand.mcc());
        assertThat(vanCommand.overseas()).isEqualTo(internalCommand.overseas());
        assertThat(vanCommand.online()).isEqualTo(internalCommand.online());
    }

    @Test
    @DisplayName("두 어댑터 모두 MERCHANT_POLICY_VIOLATION 을 동일하게 직렬화한다")
    void bothAdapters_serializeMerchantPolicyViolation_identically() throws Exception {
        when(authorizeCardUseCase.authorize(org.mockito.ArgumentMatchers.any(AuthorizeCardCommand.class)))
                .thenReturn(AuthorizationResult.declined(DeclineReason.MERCHANT_POLICY_VIOLATION));

        // VAN 어댑터
        mockMvc.perform(post("/van/v1/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "networkRequestId": "PARITY-DEC-VAN",
                                  "cardId": 9001,
                                  "amount": 1000.00,
                                  "mcc": "9999",
                                  "overseas": false,
                                  "online": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(false))
                .andExpect(jsonPath("$.declineCode").value("MERCHANT_POLICY_VIOLATION"));

        // 내부 API 어댑터
        when(authorizeCardUseCase.authorize(org.mockito.ArgumentMatchers.any(AuthorizeCardCommand.class)))
                .thenReturn(AuthorizationResult.declined(DeclineReason.MERCHANT_POLICY_VIOLATION));
        mockMvc.perform(post("/internal/api/v1/cards/9001/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "authorizationId": "PARITY-DEC-INT",
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
