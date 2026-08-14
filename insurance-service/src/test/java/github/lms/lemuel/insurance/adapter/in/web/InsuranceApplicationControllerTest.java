package github.lms.lemuel.insurance.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.insurance.application.port.in.SubmitApplicationUseCase;
import github.lms.lemuel.insurance.application.port.in.SubmitApplicationUseCase.SubmitApplicationCommand;
import github.lms.lemuel.insurance.application.port.in.UnderwriteApplicationUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 청약 접수 API 계약 테스트 — <b>접수자(fcId)가 JWT 주체에서만 온다</b>는 불변식.
 *
 * <p>접수자는 발행될 계약의 수수료 수령인이 된다. 본문 fcId 를 신뢰하던 시절에는 남의 fcId 를
 * 적는 것만으로 타인 명의 청약을 만들고 수수료를 자기 것으로 돌릴 수 있었다.
 *
 * <p>standaloneSetup 은 시큐리티 필터를 태우지 않으므로 {@link SecurityContextHolder} 를 직접 세팅한다
 * (가입설계·계약 컨트롤러 테스트와 동일 관례). 심사 전이의 <b>역할</b> 게이트는 경로 매처가 담당하며
 * {@code UnderwritingPathGateTest} 가 실제 필터 체인으로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InsuranceApplicationController — 접수자는 JWT 주체에서만 파생된다")
class InsuranceApplicationControllerTest {

    private static final String APPLICATION_ID = "11111111-2222-3333-4444-555555555555";
    /** JWT userId 100 → FC 식별자 "100" (FcIdentity 파생 규칙). */
    private static final long JWT_USER_ID = 100L;
    private static final String DERIVED_FC_ID = "100";

    @Mock SubmitApplicationUseCase submitUseCase;
    @Mock UnderwriteApplicationUseCase underwriteUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new InsuranceApplicationController(submitUseCase, underwriteUseCase)).build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** JWT 인증 주체를 SecurityContext 에 세팅한다 (userId 없는 구 토큰은 uid=null). */
    private static void authenticateAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthPrincipal(userId, "fc@example.com", "USER"), null, List.of()));
    }

    private static final String SUBMIT_BODY = """
            {"productCode":"PROD-LIFE-01","insuredName":"김피보","contractorName":"홍길동",
             "desiredCoverage":100000000,"desiredPremium":1200000,"salesChannel":"FC"}
            """;

    /** 남의 fcId 를 본문에 섞은 청약 — 바인딩 통로가 없으므로 무시돼야 한다. */
    private static final String SUBMIT_BODY_WITH_FOREIGN_FC = """
            {"productCode":"PROD-LIFE-01","insuredName":"김피보","contractorName":"홍길동",
             "desiredCoverage":100000000,"desiredPremium":1200000,"salesChannel":"FC",
             "fcId":"victim-fc-999"}
            """;

    private SubmitApplicationCommand submitAndCapture(String body) throws Exception {
        when(submitUseCase.submit(any())).thenReturn(APPLICATION_ID);

        mockMvc.perform(post("/api/insurance/applications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<SubmitApplicationCommand> captor =
                ArgumentCaptor.forClass(SubmitApplicationCommand.class);
        verify(submitUseCase).submit(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("접수자는 JWT userId 에서 파생된다")
    void fcIdComesFromJwt() throws Exception {
        authenticateAs(JWT_USER_ID);

        assertThat(submitAndCapture(SUBMIT_BODY).fcId()).isEqualTo(DERIVED_FC_ID);
    }

    @Test
    @DisplayName("본문에 남의 fcId 를 섞어도 무시된다 — 수수료 수령인을 가로챌 수 없다")
    void bodyFcIdIsIgnored() throws Exception {
        authenticateAs(JWT_USER_ID);

        SubmitApplicationCommand command = submitAndCapture(SUBMIT_BODY_WITH_FOREIGN_FC);

        assertThat(command.fcId()).isEqualTo(DERIVED_FC_ID);
        assertThat(command.fcId()).isNotEqualTo("victim-fc-999");
    }

    @Test
    @DisplayName("userId 가 없는 구 토큰은 403 — 접수 자체가 일어나지 않는다")
    void unidentifiedRequesterIsForbidden() throws Exception {
        authenticateAs(null);

        mockMvc.perform(post("/api/insurance/applications")
                        .contentType(MediaType.APPLICATION_JSON).content(SUBMIT_BODY))
                .andExpect(status().isForbidden());

        verify(submitUseCase, never()).submit(any());
    }

    @Test
    @DisplayName("미인증 요청도 403 — 접수 자체가 일어나지 않는다")
    void anonymousIsForbidden() throws Exception {
        mockMvc.perform(post("/api/insurance/applications")
                        .contentType(MediaType.APPLICATION_JSON).content(SUBMIT_BODY))
                .andExpect(status().isForbidden());

        verify(submitUseCase, never()).submit(any());
    }
}
