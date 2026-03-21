package github.lms.lemuel.common.config.jwt;

import jakarta.servlet.http.HttpServletResponse;
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
                "http://localhost:8089",
                "http://localhost:3000",
                "http://localhost:5173",  // Vite 기본 포트
                "http://127.0.0.1:3000",
                "http://127.0.0.1:8089",
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
                        // CORS Preflight 허용
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 루트 및 에러 경로
                        .requestMatchers("/", "/error").permitAll()
                        // 인증 불필요 (Public endpoints)
                        .requestMatchers(HttpMethod.POST, "/api/users").permitAll()               // 회원가입
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()          // 로그인
                        .requestMatchers(HttpMethod.POST, "/api/auth/social/login").permitAll()  // 소셜 로그인
                        .requestMatchers("/api/auth/social/**").authenticated()                   // 소셜 계정 관리
                        .requestMatchers(HttpMethod.POST, "/api/users/password-reset/**").permitAll()  // 비밀번호 재설정
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        // Actuator: 헬스체크 프로브만 공개, 메트릭/prometheus는 인증 필요
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/games/**").permitAll()
                        // 공개 카테고리 API
                        .requestMatchers(HttpMethod.GET, "/categories", "/categories/**").permitAll()
                        // 쿠폰 관련 API
                        .requestMatchers(HttpMethod.GET, "/api/coupons", "/api/coupons/**").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(HttpMethod.POST, "/api/coupons/*/usage").hasAnyRole("ADMIN", "MANAGER", "USER")
                        // 전체 주문/사용자 조회 (관리자·매니저)
                        .requestMatchers("/api/orders/admin/all").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers("/api/users/admin/all").hasRole("ADMIN")
                        // 관리자 전용 카테고리 API
                        .requestMatchers("/admin/categories/**").hasRole("ADMIN")
                        // 장바구니 API
                        .requestMatchers("/api/cart/**").hasAnyRole("ADMIN", "MANAGER", "USER")
                        // RAG API (인증 사용자)
                        .requestMatchers("/api/rag/query", "/api/rag/sessions", "/api/rag/sessions/**").authenticated()
                        // RAG 인덱싱 (관리자 전용)
                        .requestMatchers(HttpMethod.POST, "/api/rag/index").hasRole("ADMIN")
                        // 상품 검색 API (공개)
                        .requestMatchers(HttpMethod.GET, "/api/products/search/**").permitAll()
                        // 상품 검색 관리 API (관리자 전용)
                        .requestMatchers(HttpMethod.POST, "/api/products/search/**").hasRole("ADMIN")
                        // 정산 관련 API (관리자·매니저)
                        .requestMatchers("/api/settlements/**").hasAnyRole("ADMIN", "MANAGER")
                        // 알림 관련 API
                        .requestMatchers("/api/notifications/**").hasAnyRole("ADMIN", "MANAGER", "USER")
                        // 배송지 관련 API
                        .requestMatchers("/api/shipping-addresses/**").hasAnyRole("ADMIN", "MANAGER", "USER")
                        // 위시리스트 API
                        .requestMatchers("/api/wishlist/**").hasAnyRole("ADMIN", "MANAGER", "USER")
                        // 배송 관련 API (상태별 조회는 관리자·매니저만)
                        .requestMatchers("/api/deliveries/status/**").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers("/api/deliveries/**").hasAnyRole("ADMIN", "MANAGER", "USER")
                        // 반품/교환 관련 API
                        .requestMatchers("/api/returns/**").hasAnyRole("ADMIN", "MANAGER", "USER")
                        // 판매자 관련 API
                        .requestMatchers(HttpMethod.POST, "/api/sellers").hasAnyRole("ADMIN", "MANAGER", "USER")
                        .requestMatchers(HttpMethod.GET, "/api/sellers/user/*").hasAnyRole("ADMIN", "MANAGER", "USER")
                        .requestMatchers("/api/sellers/**").hasAnyRole("ADMIN", "MANAGER")
                        // 상품 변형/옵션 관련 API
                        .requestMatchers("/api/products/*/variants/**").hasAnyRole("ADMIN", "MANAGER")
                        // 포인트 관련 API (관리자 조정은 ADMIN만)
                        .requestMatchers("/api/points/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/points/**").hasAnyRole("ADMIN", "MANAGER", "USER")
                        // 나머지는 인증 필요
                        .anyRequest().authenticated()
                )
                // 미인증 요청 → 401, 권한 부족 → 403
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
                        .accessDeniedHandler((request, response, e) ->
                                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden"))
                )
                // JWT 필터 추가
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
