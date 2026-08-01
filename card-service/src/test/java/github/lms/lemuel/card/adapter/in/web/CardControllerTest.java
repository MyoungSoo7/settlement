package github.lms.lemuel.card.adapter.in.web;

import github.lms.lemuel.card.application.port.in.IssueCardUseCase;
import github.lms.lemuel.card.application.port.in.OpenCardAccountUseCase;
import github.lms.lemuel.card.application.port.out.FundingUnavailableException;
import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardAccountStatus;
import github.lms.lemuel.card.domain.CardStatus;
import github.lms.lemuel.card.domain.LimitSnapshot;
import github.lms.lemuel.card.domain.ReputationGrade;
import github.lms.lemuel.card.domain.exception.SubLimitExceededException;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 카드 REST 표면 테스트. 검증 대상은 <b>상태코드 매핑과 주체 파생</b>이다 —
 * 심사 로직은 {@code OpenCardAccountServiceTest} 가, 산식은 도메인 테스트가 이미 고정한다.
 */
@WebMvcTest(controllers = CardController.class)
@AutoConfigureMockMvc(addFilters = false)
// CardServiceApplication 이 @EnableCaching 이라 슬라이스에도 CacheManager 빈이 있어야 한다.
// 목 대신 실물(Caffeine)을 넣는다 — 목 CacheManager 는 캐시 경로가 생기는 순간 조용히 NPE 를 낸다.
@Import(github.lms.lemuel.common.config.CacheConfig.class)
class CardControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean OpenCardAccountUseCase openCardAccountUseCase;
    @MockitoBean IssueCardUseCase issueCardUseCase;

    private static final String BODY = "{\"organizationId\":3001}";

    /** 일반 사용자 주체 — 조직 역할 판정은 서비스(CardOrgAuthorizer)가 하고 여기선 uid 만 본다. */
    private static Authentication userAuth(long userId) {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(userId, "u" + userId + "@example.com", "USER"),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static CardAccount activeAccount() {
        CardAccount account = CardAccount.builder()
                .id(5001L)
                .organizationId(3001L)
                .sellerId("777")
                .status(CardAccountStatus.SCREENING)
                .masterLimit(BigDecimal.ZERO)
                .build();
        account.activate(new BigDecimal("700000"), new LimitSnapshot(
                new BigDecimal("800000"), new BigDecimal("200000"),
                new BigDecimal("0.70"), ReputationGrade.B, "formula"));
        return account;
    }

    @Test
    @DisplayName("개설 성공은 201 과 한도·산정 근거를 함께 돌려준다")
    void openAccountReturns201() throws Exception {
        when(openCardAccountUseCase.open(any())).thenReturn(activeAccount());

        mockMvc.perform(post("/api/cards/accounts").principal(userAuth(100L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5001))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.masterLimit").value(700000))
                .andExpect(jsonPath("$.reputationGrade").value("B"))
                .andExpect(jsonPath("$.appliedRatio").value(0.70));
    }

    /** 본문의 사용자 식별자를 받지 않는다는 계약 — 요청자는 오직 JWT 주체에서 나온다. */
    @Test
    @DisplayName("요청자 userId 는 JWT 주체에서 파생된다 — 본문이 아니다")
    void requesterComesFromPrincipal() throws Exception {
        when(openCardAccountUseCase.open(any())).thenReturn(activeAccount());

        mockMvc.perform(post("/api/cards/accounts").principal(userAuth(4242L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationId\":3001,\"requesterUserId\":1}"))
                .andExpect(status().isCreated());

        verify(openCardAccountUseCase).open(
                new OpenCardAccountUseCase.OpenCardAccountCommand(3001L, 4242L));
    }

    @Test
    @DisplayName("인증 주체가 없으면 403 이고 유스케이스를 호출하지 않는다")
    void missingPrincipalIsForbidden() throws Exception {
        mockMvc.perform(post("/api/cards/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));

        verify(openCardAccountUseCase, never()).open(any());
    }

    @Test
    @DisplayName("조직 역할이 부족하면 403 + CARD_FORBIDDEN")
    void forbiddenRoleIs403() throws Exception {
        when(openCardAccountUseCase.open(any()))
                .thenThrow(new BusinessException(ErrorCode.CARD_FORBIDDEN));

        mockMvc.perform(post("/api/cards/accounts").principal(userAuth(200L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("CARD_FORBIDDEN"));
    }

    @Test
    @DisplayName("심사 탈락은 422 + CARD_SCREENING_REJECTED 로 사유가 그대로 전달된다")
    void screeningRejectedIs422() throws Exception {
        when(openCardAccountUseCase.open(any())).thenThrow(new BusinessException(
                ErrorCode.CARD_SCREENING_REJECTED, "평판 등급 E 는 카드 발급 대상이 아닙니다."));

        mockMvc.perform(post("/api/cards/accounts").principal(userAuth(100L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CARD_SCREENING_REJECTED"))
                .andExpect(jsonPath("$.message").value("평판 등급 E 는 카드 발급 대상이 아닙니다."));
    }

    @Test
    @DisplayName("중복 개설은 409")
    void duplicateIs409() throws Exception {
        when(openCardAccountUseCase.open(any()))
                .thenThrow(new BusinessException(ErrorCode.CARD_ACCOUNT_ALREADY_EXISTS));

        mockMvc.perform(post("/api/cards/accounts").principal(userAuth(100L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CARD_ACCOUNT_ALREADY_EXISTS"));
    }

    /**
     * 번역을 잊은 경로가 생겨도 500 이 아니라 503 으로 나가야 한다 — 재시도 가능한 장애임을
     * 클라이언트가 구분할 수 있어야 한다(500 은 "다시 걸어도 소용없다"로 읽힌다).
     */
    @Test
    @DisplayName("번역되지 않은 재원 조회 실패도 503 으로 나간다")
    void rawFundingFailureIs503() throws Exception {
        when(openCardAccountUseCase.open(any()))
                .thenThrow(new FundingUnavailableException("account down"));

        mockMvc.perform(post("/api/cards/accounts").principal(userAuth(100L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("CARD_FUNDING_UNAVAILABLE"));
    }

    @Test
    @DisplayName("organizationId 가 없으면 400 — 유스케이스까지 가지 않는다")
    void missingOrganizationIdIs400() throws Exception {
        mockMvc.perform(post("/api/cards/accounts").principal(userAuth(100L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(openCardAccountUseCase, never()).open(any());
    }

    // ── 카드 발급 (POST /accounts/{id}/cards) ──

    private static final String ISSUE_BODY = "{\"holderUserId\":888,\"subLimit\":100000}";

    private static Card issuedCard() {
        return Card.builder()
                .id(9001L)
                .cardAccountId(5001L)
                .holderUserId(888L)
                .maskedCardNo("****-****-****-1234")
                .subLimit(new BigDecimal("100000"))
                .status(CardStatus.ISSUED)
                .build();
    }

    @Test
    @DisplayName("발급 성공은 201 과 마스킹된 번호를 돌려준다")
    void issueCardReturns201() throws Exception {
        when(issueCardUseCase.issue(any())).thenReturn(issuedCard());

        mockMvc.perform(post("/api/cards/accounts/5001/cards").principal(userAuth(100L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ISSUE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(9001))
                .andExpect(jsonPath("$.holderUserId").value(888))
                .andExpect(jsonPath("$.maskedCardNo").value("****-****-****-1234"))
                .andExpect(jsonPath("$.subLimit").value(100000))
                .andExpect(jsonPath("$.status").value("ISSUED"));
    }

    /**
     * 발급은 세 값의 출처가 다르다 — 카드계정은 경로, 대상은 본문, 요청자는 JWT.
     * 본문의 requesterUserId 를 흘려 넣어도 무시돼야 한다(권한 상승 경로 차단).
     */
    @Test
    @DisplayName("대상은 본문·요청자는 JWT 에서 온다 — 본문의 requesterUserId 는 무시된다")
    void holderFromBodyRequesterFromPrincipal() throws Exception {
        when(issueCardUseCase.issue(any())).thenReturn(issuedCard());

        mockMvc.perform(post("/api/cards/accounts/5001/cards").principal(userAuth(100L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holderUserId\":888,\"subLimit\":100000,\"requesterUserId\":1}"))
                .andExpect(status().isCreated());

        verify(issueCardUseCase).issue(new IssueCardUseCase.IssueCardCommand(
                5001L, 888L, new BigDecimal("100000"), 100L));
    }

    @Test
    @DisplayName("대상이 조직 구성원이 아니면 422 + CARD_HOLDER_NOT_MEMBER")
    void holderNotMemberIs422() throws Exception {
        when(issueCardUseCase.issue(any()))
                .thenThrow(new BusinessException(ErrorCode.CARD_HOLDER_NOT_MEMBER));

        mockMvc.perform(post("/api/cards/accounts/5001/cards").principal(userAuth(100L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ISSUE_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CARD_HOLDER_NOT_MEMBER"));
    }

    @Test
    @DisplayName("이미 활성 카드가 있으면 409 + CARD_ALREADY_ISSUED")
    void alreadyIssuedIs409() throws Exception {
        when(issueCardUseCase.issue(any()))
                .thenThrow(new BusinessException(ErrorCode.CARD_ALREADY_ISSUED));

        mockMvc.perform(post("/api/cards/accounts/5001/cards").principal(userAuth(100L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ISSUE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CARD_ALREADY_ISSUED"));
    }

    /** 도메인 예외({@code SubLimitExceededException})가 CardExceptionHandler 로 422 가 되는 경로. */
    @Test
    @DisplayName("마스터 한도 초과는 422 + CARD_SUB_LIMIT_EXCEEDED 이고 한도 수치가 메시지에 남는다")
    void subLimitExceededIs422() throws Exception {
        when(issueCardUseCase.issue(any())).thenThrow(new SubLimitExceededException(
                new BigDecimal("1000000"), new BigDecimal("950000"), new BigDecimal("100000")));

        mockMvc.perform(post("/api/cards/accounts/5001/cards").principal(userAuth(100L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ISSUE_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CARD_SUB_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("1000000")));
    }

    @Test
    @DisplayName("subLimit 이 없으면 400 — 유스케이스까지 가지 않는다")
    void missingSubLimitIs400() throws Exception {
        mockMvc.perform(post("/api/cards/accounts/5001/cards").principal(userAuth(100L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holderUserId\":888}"))
                .andExpect(status().isBadRequest());

        verify(issueCardUseCase, never()).issue(any());
    }

    @Test
    @DisplayName("발급도 인증 주체가 없으면 403 이고 유스케이스를 호출하지 않는다")
    void issueWithoutPrincipalIsForbidden() throws Exception {
        mockMvc.perform(post("/api/cards/accounts/5001/cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ISSUE_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));

        verify(issueCardUseCase, never()).issue(any());
    }
}
