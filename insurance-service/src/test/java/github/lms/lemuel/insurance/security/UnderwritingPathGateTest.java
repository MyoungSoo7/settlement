package github.lms.lemuel.insurance.security;

import github.lms.lemuel.common.config.CacheConfig;
import github.lms.lemuel.common.config.jwt.InternalApiKeyFilter;
import github.lms.lemuel.common.config.jwt.JwtAuthenticationFilter;
import github.lms.lemuel.common.config.jwt.JwtProperties;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.common.config.jwt.SecurityConfig;
import github.lms.lemuel.insurance.adapter.in.web.InsuranceApplicationController;
import github.lms.lemuel.insurance.application.port.in.SubmitApplicationUseCase;
import github.lms.lemuel.insurance.application.port.in.UnderwriteApplicationUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 언더라이팅 전이 경로({@code /api/insurance/applications/*&#47;{review,approve,reject}})의 게이트 회귀 가드.
 *
 * <p>배경: 승인은 <b>계약을 발행하고 수수료 12회를 확정</b>하는 행위다. 그런데 이 경로들은
 * shared-common {@code SecurityConfig} 의 경로별 매처 목록에 없어 최종 규칙
 * {@code anyRequest().authenticated()} 로 떨어져 있었다 — 청약 UUID 만 알면 아무 로그인 사용자나
 * 남의 청약을 승인시킬 수 있었다는 뜻이다.
 *
 * <p>기대 계약: 접수자(FC)와 심사자는 같은 권한일 수 없다. 심사 전이는 백오피스 역할
 * (ADMIN/MANAGER)만 호출한다.
 *
 * <p>이 테스트가 필요한 이유: 저장소에 {@code @EnableMethodSecurity} 가 없어 {@code @PreAuthorize} 는
 * 조용히 무시된다. 인가는 오직 이 경로 매처 목록으로만 걸리므로, 매처 패턴이 어긋나면 게이트는
 * 아무 소리 없이 사라진다. 그래서 "선언"이 아니라 <b>실제 응답 코드</b>로 고정한다.
 *
 * <p>인증 주입은 {@code springSecurity()} 컨피규러 + {@code user()} 후처리기로 한다. 체인이 STATELESS 라
 * 세션 기반 {@code @WithMockUser} 는 컨텍스트가 로드되지 않아 401 로만 떨어지고 게이트를 타지 못한다
 * (card {@code VanPathGateTest} 와 동일 관례).
 */
@WebMvcTest(controllers = InsuranceApplicationController.class)
@Import({SecurityConfig.class, CacheConfig.class, UnderwritingPathGateTest.TestFilters.class})
@TestPropertySource(properties = "cors.origins=http://localhost:3000")
class UnderwritingPathGateTest {

    private static final String APPLICATION_ID = "11111111-2222-3333-4444-555555555555";
    private static final String APPROVE = "/api/insurance/applications/" + APPLICATION_ID + "/approve";
    private static final String REVIEW = "/api/insurance/applications/" + APPLICATION_ID + "/review";
    private static final String REJECT = "/api/insurance/applications/" + APPLICATION_ID + "/reject";
    private static final String REJECT_BODY = """
            {"reason":"고지의무 위반"}
            """;

    @Autowired WebApplicationContext context;

    private MockMvc mockMvc;

    @MockitoBean SubmitApplicationUseCase submitUseCase;
    @MockitoBean UnderwriteApplicationUseCase underwriteUseCase;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private int statusOf(String path, String body, String role) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (role != null) {
            request = request.with(user("someone@lemuel.dev").roles(role));
        }
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("일반 사용자 토큰으로는 청약을 승인할 수 없다 → 403 (계약 발행·수수료 확정 경로)")
    void userRoleCannotApprove() throws Exception {
        assertThat(statusOf(APPROVE, "", "USER")).isEqualTo(403);
    }

    @Test
    @DisplayName("일반 사용자 토큰으로는 심사에 착수할 수 없다 → 403")
    void userRoleCannotStartReview() throws Exception {
        assertThat(statusOf(REVIEW, "", "USER")).isEqualTo(403);
    }

    @Test
    @DisplayName("일반 사용자 토큰으로는 청약을 반려할 수 없다 → 403")
    void userRoleCannotReject() throws Exception {
        assertThat(statusOf(REJECT, REJECT_BODY, "USER")).isEqualTo(403);
    }

    @Test
    @DisplayName("미인증 요청은 401")
    void anonymousIsUnauthorized() throws Exception {
        assertThat(statusOf(APPROVE, "", null)).isEqualTo(401);
    }

    /**
     * 과잉 차단 방지 가드 — 게이트를 세우다 심사 자체를 막으면 언더라이팅이 통째로 멈춘다.
     * 유스케이스는 목이라 본문까지 성립시키지 않고 "인가 단계를 통과했다"만 확인한다.
     */
    @Test
    @DisplayName("ADMIN·MANAGER 는 통과한다 (401·403 아님)")
    void backOfficeRolesPass() throws Exception {
        assertThat(statusOf(APPROVE, "", "ADMIN")).isNotIn(401, 403);
        assertThat(statusOf(APPROVE, "", "MANAGER")).isNotIn(401, 403);
    }

    @TestConfiguration
    static class TestFilters {

        @Bean
        JwtUtil jwtUtil() {
            JwtProperties props = new JwtProperties();
            props.setIssuer("t");
            props.setSecret("this-is-a-test-secret-key-must-be-at-least-32-bytes-long");
            props.setTtlSeconds(3600);
            return new JwtUtil(props);
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter(JwtUtil jwtUtil) {
            return new JwtAuthenticationFilter(jwtUtil);
        }

        @Bean
        InternalApiKeyFilter internalApiKeyFilter() {
            return new InternalApiKeyFilter("k");
        }
    }
}
