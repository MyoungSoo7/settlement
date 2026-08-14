package github.lms.lemuel.ai.config;

import github.lms.lemuel.common.config.jwt.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * {@code /api/ai/**} 전용 보안 체인 (operation-service 패턴).
 *
 * <p>common.config.jwt 스캔으로 shared-common 의 전역 SecurityConfig(순서 미지정 = 최후순)도
 * 함께 뜨므로, 이 체인을 {@code @Order(1)} + securityMatcher 로 앞에 세워 챗봇 경로만 가로챈다.
 * 나머지 경로(actuator/swagger 등)는 전역 체인이 그대로 처리한다.
 *
 * <p>LLM 호출은 실비용이므로 익명·GUEST 개방 불가 — USER 이상 역할만 허용(설계 §2.2).
 *
 * <p><b>지식베이스 적재·삭제만 ADMIN 으로 한 단계 더 올린다</b>(ADR 0034). 적재된 텍스트는
 * 이후 모든 사용자의 답변 근거로 시스템 프롬프트에 실리므로, 적재 권한은 사실상 챗봇의 발언에
 * 대한 쓰기 권한이다 — USER 에게 열어 두면 영구 저장되는 프롬프트 인젝션 창구가 된다.
 * 조회({@code GET /search})는 진단 목적이라 USER 이상에 열어 둔다.
 */
@Configuration
public class AiSecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public AiSecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    @Order(1)
    // CSRF 비활성 경고(java:S4502) 억제 — 이 체인은 세션을 만들지 않는(STATELESS) 토큰 API 다.
    // 브라우저가 자동으로 실어 보내는 자격증명(쿠키·Basic)을 인증에 쓰지 않으므로 교차 사이트
    // 요청이 사용자 권한을 획득할 수 없다 — CSRF 토큰이 방어할 대상 자체가 없다.
    // 세션·쿠키 인증을 도입하는 순간 이 억제는 무효이며 CSRF 보호를 되살려야 한다.
    @SuppressWarnings("java:S4502")
    public SecurityFilterChain aiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/ai/**")
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 지식 문서 적재/삭제 = 챗봇 답변 근거에 대한 쓰기 → ADMIN 전용
                        .requestMatchers("/api/ai/knowledge/documents",
                                "/api/ai/knowledge/documents/**").hasRole("ADMIN")
                        .anyRequest().hasAnyRole("USER", "MANAGER", "ADMIN")
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
                        .accessDeniedHandler((request, response, e) ->
                                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden"))
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
