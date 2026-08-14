package github.lms.lemuel.financial.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * financial-statements-service 자체 최소 보안 설정.
 *
 * <p>이 서비스가 다루는 재무제표는 전부 공시된 공개 데이터라 조회(GET)는 무인증이다.
 * 유일한 쓰기 경로인 DART 수집 트리거(/admin/financial/**)는 {@link AdminApiKeyFilter} 가
 * X-Internal-Api-Key 공유 시크릿으로 게이팅한다(order 의 InternalApiKeyFilter 와 동일 시맨틱).
 * shared-common JWT 스택을 쓰지 않는 이유는 FinancialStatementsApplication javadoc 참조.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AdminApiKeyFilter adminApiKeyFilter;

    @Value("${cors.origins:${cors.allowed-origins:}}")
    private String corsAllowedOrigins;

    public SecurityConfig(AdminApiKeyFilter adminApiKeyFilter) {
        this.adminApiKeyFilter = adminApiKeyFilter;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        if (corsAllowedOrigins != null && !corsAllowedOrigins.isBlank()) {
            configuration.setAllowedOrigins(Arrays.asList(corsAllowedOrigins.split(",")));
        } else {
            configuration.setAllowedOrigins(List.of(
                    "http://localhost:8089",
                    "http://localhost:3000",
                    "http://localhost:5173",
                    "http://127.0.0.1:3000",
                    "http://127.0.0.1:8089",
                    "http://127.0.0.1:5173"
            ));
        }
        configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    // CSRF 비활성 경고(java:S4502) 억제 — 이 체인은 세션을 만들지 않는(STATELESS) 토큰 API 다.
    // 브라우저가 자동으로 실어 보내는 자격증명(쿠키·Basic)을 인증에 쓰지 않으므로 교차 사이트
    // 요청이 사용자 권한을 획득할 수 없다 — CSRF 토큰이 방어할 대상 자체가 없다.
    // 세션·쿠키 인증을 도입하는 순간 이 억제는 무효이며 CSRF 보호를 되살려야 한다.
    @SuppressWarnings("java:S4502")
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/", "/error").permitAll()
                        // 공개 조회 API — 공시 데이터이므로 무인증
                        .requestMatchers(HttpMethod.GET, "/api/financial/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/actuator/info", "/actuator/prometheus").permitAll()
                        // DART 수집 트리거 — 인증 프레임워크상 permitAll 이지만 AdminApiKeyFilter 가 게이팅
                        .requestMatchers("/admin/financial/**").permitAll()
                        .anyRequest().denyAll()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
                        .accessDeniedHandler((request, response, e) ->
                                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden"))
                )
                .addFilterBefore(adminApiKeyFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
