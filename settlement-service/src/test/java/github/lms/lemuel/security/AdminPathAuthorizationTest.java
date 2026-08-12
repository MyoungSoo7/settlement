package github.lms.lemuel.security;

import github.lms.lemuel.closing.adapter.in.web.ClosingAdminController;
import github.lms.lemuel.closing.application.port.in.GetMonthlyClosingUseCase;
import github.lms.lemuel.closing.application.port.in.RunMonthlyClosingUseCase;
import github.lms.lemuel.common.config.JacksonCompatConfig;
import github.lms.lemuel.common.config.jwt.InternalApiKeyFilter;
import github.lms.lemuel.common.config.jwt.JwtAuthenticationFilter;
import github.lms.lemuel.common.config.jwt.JwtProperties;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.common.config.jwt.SecurityConfig;
import github.lms.lemuel.ledger.adapter.in.web.LedgerPeriodAdminController;
import github.lms.lemuel.ledger.application.port.in.CloseLedgerPeriodUseCase;
import github.lms.lemuel.ledger.application.port.in.GetLedgerPeriodUseCase;
import github.lms.lemuel.ledger.application.port.in.GetLedgerTrialBalanceUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 전용 경로의 역할 게이트 회귀 가드.
 *
 * <p>배경: 정산 코어의 {@code /admin/**} 경로는 shared-common {@code SecurityConfig} 의
 * <b>경로별 열거 매처</b>로 인가된다 — {@code /admin/**} 포괄 매처는 존재하지 않는다. 따라서 새 관리자
 * 컨트롤러를 추가하고 매처 등록을 잊으면 그 경로는 조용히 최종 규칙({@code anyRequest().authenticated()})으로
 * 떨어져 <b>일반 USER 토큰으로도 호출 가능</b>해진다. 컴파일도 기존 테스트도 이를 잡지 못한다
 * (기존 컨트롤러 테스트는 {@code addFilters = false} 로 보안 체인을 통째로 우회한다).
 *
 * <p>여기서 지키는 두 경로는 되돌리기 어려운 회계 조작이라 특히 위험하다:
 * <ul>
 *   <li>{@code POST /admin/monthly-closing/{ym}/run} — 정보계 월마감 마트 전체 교체</li>
 *   <li>{@code POST /admin/ledger-periods/{ym}/close} — 원장 기간 마감·잠금(재개봉 없음)</li>
 * </ul>
 *
 * <p>기대 계약은 두 컨트롤러가 스스로 선언한 것과 같다(@Tag "... (ADMIN)"): <b>ADMIN 만 통과</b>.
 *
 * <p><b>인증 주입 방식</b>: 체인이 {@code SessionCreationPolicy.STATELESS} 라 세션 기반
 * {@code @WithMockUser} 는 컨텍스트가 로드되지 않아 전부 401 로 떨어진다(= 역할 게이트를 타지도 못한다).
 * 그래서 {@code springSecurity()} 컨피규러를 적용한 MockMvc 를 직접 만들고 요청마다
 * {@code user(...).roles(...)} 후처리기로 주체를 주입한다 — 이래야 401(미인증)과 403(권한부족)이 구분된다.
 */
@WebMvcTest(controllers = {ClosingAdminController.class, LedgerPeriodAdminController.class})
@Import({SecurityConfig.class, JacksonCompatConfig.class, AdminPathAuthorizationTest.TestFilters.class})
@TestPropertySource(properties = "cors.origins=http://localhost:3000")
class AdminPathAuthorizationTest {

    private static final String MONTHLY_CLOSING_RUN = "/admin/monthly-closing/2026-07/run";
    private static final String LEDGER_PERIOD_CLOSE = "/admin/ledger-periods/2026-07/close";

    @Autowired WebApplicationContext context;

    private MockMvc mockMvc;

    @MockitoBean RunMonthlyClosingUseCase runMonthlyClosingUseCase;
    @MockitoBean GetMonthlyClosingUseCase getMonthlyClosingUseCase;
    @MockitoBean CloseLedgerPeriodUseCase closeLedgerPeriodUseCase;
    @MockitoBean GetLedgerPeriodUseCase getLedgerPeriodUseCase;
    @MockitoBean GetLedgerTrialBalanceUseCase getLedgerTrialBalanceUseCase;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private int statusFor(String path, String role) throws Exception {
        return mockMvc.perform(post(path).with(user("operator@lemuel.dev").roles(role)))
                .andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("USER 토큰으로 월마감 실행 → 403")
    void userCannotRunMonthlyClosing() throws Exception {
        assertThat(statusFor(MONTHLY_CLOSING_RUN, "USER")).isEqualTo(403);
    }

    @Test
    @DisplayName("USER 토큰으로 원장 기간 마감 → 403")
    void userCannotCloseLedgerPeriod() throws Exception {
        assertThat(statusFor(LEDGER_PERIOD_CLOSE, "USER")).isEqualTo(403);
    }

    @Test
    @DisplayName("MANAGER 토큰으로 월마감 실행 → 403 (선언된 계약은 ADMIN 전용)")
    void managerCannotRunMonthlyClosing() throws Exception {
        assertThat(statusFor(MONTHLY_CLOSING_RUN, "MANAGER")).isEqualTo(403);
    }

    @Test
    @DisplayName("MANAGER 토큰으로 원장 기간 마감 → 403 (선언된 계약은 ADMIN 전용)")
    void managerCannotCloseLedgerPeriod() throws Exception {
        assertThat(statusFor(LEDGER_PERIOD_CLOSE, "MANAGER")).isEqualTo(403);
    }

    @Test
    @DisplayName("미인증 요청은 401 — 인증과 인가가 구분되어 동작함을 확인(테스트 자체의 유효성 가드)")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(post(MONTHLY_CLOSING_RUN)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(LEDGER_PERIOD_CLOSE)).andExpect(status().isUnauthorized());
    }

    /**
     * 과잉 차단 방지 가드 — 인가 규칙을 추가하다 ADMIN 까지 막아버리면 운영이 마비된다.
     * 유스케이스는 목이라 본문 응답까지 성립시키지 않고, "인가 단계에서 막히지 않았다"만 확인한다.
     */
    @Test
    @DisplayName("ADMIN 토큰은 두 경로 모두 인가 단계를 통과한다(401·403 아님)")
    void adminPassesAuthorization() throws Exception {
        assertThat(statusFor(MONTHLY_CLOSING_RUN, "ADMIN")).isNotIn(401, 403);
        assertThat(statusFor(LEDGER_PERIOD_CLOSE, "ADMIN")).isNotIn(401, 403);
    }

    /**
     * SecurityConfig 생성자가 요구하는 필터 빈 — 실제 체인을 빌드하기 위한 최소 구성
     * (shared-common {@code SecurityConfigContextTest} 와 동형).
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
        InternalApiKeyFilter internalApiKeyFilter() {
            return new InternalApiKeyFilter("test-internal-key");
        }
    }
}
