package github.lms.lemuel.card.security;

import github.lms.lemuel.card.adapter.in.web.AuthorizationVanAdapter;
import github.lms.lemuel.card.application.port.in.AuthorizeCardUseCase;
import github.lms.lemuel.common.config.CacheConfig;
import github.lms.lemuel.common.config.jwt.InternalApiKeyFilter;
import github.lms.lemuel.common.config.jwt.JwtAuthenticationFilter;
import github.lms.lemuel.common.config.jwt.JwtProperties;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.common.config.jwt.SecurityConfig;
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
 * VAN 진입 경로({@code /van/**})의 게이트 회귀 가드.
 *
 * <p>배경: VAN 어댑터는 카드 승인·매입·취소·환불 전문을 받는다 — 즉 <b>돈이 움직이는 진입점</b>이다.
 * 그런데 이 경로는 shared-common {@code SecurityConfig} 의 경로별 매처 목록에 없어 최종 규칙
 * {@code anyRequest().authenticated()} 로 떨어져 있었다. 유효한 사용자 JWT 하나만 있으면 누구나
 * 카드 거래를 위조할 수 있다는 뜻이다(게이트웨이가 이 경로를 라우팅하지 않는 것이 유일한 방어였다).
 *
 * <p>기대 계약: VAN 은 사람이 아니라 기계다. 사용자 토큰이 아니라 <b>공유 시크릿 헤더</b>
 * ({@link InternalApiKeyFilter#HEADER})로 통과해야 한다 — 형제 경로 {@code /internal/**} 과 같은 방식이다.
 *
 * <p>인증 주입은 {@code springSecurity()} 컨피규러 + {@code user()} 후처리기로 한다. 체인이 STATELESS 라
 * 세션 기반 {@code @WithMockUser} 는 컨텍스트가 로드되지 않아 401 로만 떨어지고 게이트를 타지 못한다.
 */
@WebMvcTest(controllers = AuthorizationVanAdapter.class)
@Import({SecurityConfig.class, CacheConfig.class, VanPathGateTest.TestFilters.class})
@TestPropertySource(properties = {
        "cors.origins=http://localhost:3000",
        "app.internal.api-key=van-test-secret"
})
class VanPathGateTest {

    private static final String VAN_AUTHORIZE = "/van/v1/authorizations";
    private static final String BODY = """
            {"networkRequestId":"AUTH-TEST-0001","cardId":9001,"amount":"45000",
             "merchantName":"스타벅스 강남점","mcc":"5814","overseas":false,"online":false}
            """;

    @Autowired WebApplicationContext context;

    private MockMvc mockMvc;

    @MockitoBean AuthorizeCardUseCase authorizeCardUseCase;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("사용자 JWT 만으로는 VAN 승인 전문을 밀어넣을 수 없다 → 401")
    void userTokenAloneCannotPostVanAuthorization() throws Exception {
        int status = mockMvc.perform(post(VAN_AUTHORIZE)
                        .with(user("seller@lemuel.dev").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(401);
    }

    @Test
    @DisplayName("ADMIN 토큰이어도 공유 시크릿 없이는 통과하지 못한다 → 401 (사람 권한으로 여는 문이 아니다)")
    void adminTokenAloneCannotPostVanAuthorization() throws Exception {
        int status = mockMvc.perform(post(VAN_AUTHORIZE)
                        .with(user("admin@lemuel.dev").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(401);
    }

    @Test
    @DisplayName("잘못된 공유 시크릿 → 401")
    void wrongSharedSecretIsRejected() throws Exception {
        int status = mockMvc.perform(post(VAN_AUTHORIZE)
                        .header(InternalApiKeyFilter.HEADER, "wrong-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(401);
    }

    /**
     * 과잉 차단 방지 가드 — 게이트를 세우다 VAN 자체를 막으면 카드 승인이 통째로 멈춘다.
     * 유스케이스는 목이라 본문까지 성립시키지 않고 "인증·인가 단계를 통과했다"만 확인한다.
     */
    @Test
    @DisplayName("올바른 공유 시크릿이면 사용자 토큰 없이도 통과한다(401·403 아님)")
    void correctSharedSecretPassesWithoutUserToken() throws Exception {
        int status = mockMvc.perform(post(VAN_AUTHORIZE)
                        .header(InternalApiKeyFilter.HEADER, "van-test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andReturn().getResponse().getStatus();

        assertThat(status).isNotIn(401, 403);
    }

    /**
     * SecurityConfig 생성자가 요구하는 필터 빈 — 실제 체인을 빌드하기 위한 최소 구성.
     * InternalApiKeyFilter 는 프로퍼티(app.internal.api-key)를 읽는 생성자를 그대로 쓴다.
     */
    @TestConfiguration
    static class TestFilters {

        @Bean
        JwtUtil jwtUtil() {
            JwtProperties props = new JwtProperties();
            props.setIssuer("test");
            props.setSecret("this-is-a-test-secret-key-must-be-at-least-32-bytes-long");
            props.setTtlSeconds(3600);
            return new JwtUtil(props);
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter(JwtUtil jwtUtil) {
            return new JwtAuthenticationFilter(jwtUtil);
        }

        @Bean
        InternalApiKeyFilter internalApiKeyFilter(
                @org.springframework.beans.factory.annotation.Value("${app.internal.api-key:}") String apiKey) {
            return new InternalApiKeyFilter(apiKey);
        }
    }
}
