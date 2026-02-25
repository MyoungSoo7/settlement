package github.lms.lemuel.common.config.jwt;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * CORS 설정
     * React 프론트엔드(localhost:3000)와의 통신을 허용합니다.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // 허용할 Origin (React 개발 서버)
        configuration.setAllowedOrigins(Arrays.asList(
                "http://localhost:3000",
                "http://localhost:5173",  // Vite 기본 포트
                "http://127.0.0.1:3000",
                "http://127.0.0.1:5173"
        ));

        // 허용할 HTTP 메서드
        configuration.setAllowedMethods(Arrays.asList(
                "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
        ));

        // 허용할 헤더
        configuration.setAllowedHeaders(Arrays.asList(
                "Authorization",
                "Content-Type",
                "X-Requested-With",
                "Idempotency-Key"  // 환불 API에서 사용
        ));

        // 노출할 헤더
        configuration.setExposedHeaders(Arrays.asList(
                "Authorization",
                "X-Total-Count"
        ));

        // 자격 증명 허용 (쿠키, Authorization 헤더 등)
        configuration.setAllowCredentials(true);

        // 프리플라이트 요청 캐싱 시간 (초)
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CORS 설정 활성화
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // CSRF 비활성화 (JWT 사용 시 불필요)
                .csrf(csrf -> csrf.disable())
                // Form Login 비활성화
                .formLogin(form -> form.disable())
                // HTTP Basic 비활성화
                .httpBasic(basic -> basic.disable())
                // Stateless 세션 관리
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 요청별 인증 설정
                .authorizeHttpRequests(auth -> auth
                        // 인증 불필요 (Public endpoints)
                        .requestMatchers(HttpMethod.POST, "/users").permitAll()               // 회원가입
                        .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()          // 로그인
                        .requestMatchers(HttpMethod.POST, "/users/password-reset/**").permitAll()  // 비밀번호 재설정
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/games/**").permitAll()
                        // 공개 카테고리 API
                        .requestMatchers(HttpMethod.GET, "/categories", "/categories/**").permitAll()
                        // 관리자 전용 카테고리 API
                        .requestMatchers("/admin/categories/**").hasRole("ADMIN")
                        // 나머지는 인증 필요
                        .anyRequest().authenticated()
                )
                // JWT 필터 추가
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
