package github.lms.lemuel.market.config;

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
 * market-service 자체 최소 보안 설정.
 *
 * <p>이 서비스가 다루는 시세·시가총액은 전부 공공데이터포털로 공개된 시장 데이터라 조회(GET)는 무인증이다.
 * 유일한 쓰기 경로인 KRX 수집 트리거(/admin/market/**)는 {@link AdminApiKeyFilter} 가
 * X-Internal-Api-Key 공유 시크릿으로 게이팅한다(order 의 InternalApiKeyFilter 와 동일 시맨틱).
 * shared-common JWT 스택을 쓰지 않는 이유는 MarketApplication javadoc 참조.
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
                        // 공개 조회 API — 공공데이터포털 공개 데이터이므로 무인증
                        .requestMatchers(HttpMethod.GET, "/api/market/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        // actuator/prometheus 는 permitAll 이지만 gateway 라우트에 없어 외부 미노출 —
                        // 운영에서는 NetworkPolicy 로 클러스터 내부(스크래퍼)만 접근하도록 격리 권장.
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/actuator/info", "/actuator/prometheus").permitAll()
                        // KRX 수집 트리거 — 인증 프레임워크상 permitAll 이지만 AdminApiKeyFilter 가 게이팅
                        .requestMatchers("/admin/market/**").permitAll()
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
