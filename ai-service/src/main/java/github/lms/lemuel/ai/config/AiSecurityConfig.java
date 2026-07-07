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
 */
@Configuration
public class AiSecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public AiSecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain aiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/ai/**")
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
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
