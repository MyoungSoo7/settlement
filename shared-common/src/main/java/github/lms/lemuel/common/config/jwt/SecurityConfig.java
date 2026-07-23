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
    private final InternalApiKeyFilter internalApiKeyFilter;

    // helm-deploy 차트가 CORS_ORIGINS 환경변수로 주입하므로 cors.origins 우선,
    // 하위호환으로 cors.allowed-origins fallback.
    @org.springframework.beans.factory.annotation.Value("${cors.origins:${cors.allowed-origins:}}")
    private String corsAllowedOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          InternalApiKeyFilter internalApiKeyFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.internalApiKeyFilter = internalApiKeyFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }




    /**
     * CORS 설정
     * React 프론트엔드(localhost:3000)와의 통신을 허용합니다.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // CORS origin: 환경변수 우선, 없으면 localhost (개발용)
        if (corsAllowedOrigins != null && !corsAllowedOrigins.isBlank()) {
            configuration.setAllowedOrigins(Arrays.asList(corsAllowedOrigins.split(",")));
        } else {
            configuration.setAllowedOrigins(Arrays.asList(
                    "http://localhost:8089",
                    "http://localhost:3000",
                    "http://localhost:5173",
                    "http://127.0.0.1:3000",
                    "http://127.0.0.1:8089",
                    "http://127.0.0.1:5173"
            ));
        }

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
                        .requestMatchers(HttpMethod.POST, "/users").permitAll()               // 회원가입
                        .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()          // 로그인
                        .requestMatchers(HttpMethod.POST, "/auth/dev/**").permitAll()         // 데모 자동로그인/게스트 (lemuel.demo.enabled=true 시)
                        .requestMatchers(HttpMethod.POST, "/users/password-reset/**").permitAll()  // 비밀번호 재설정
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        // Actuator: 헬스체크 프로브 + prometheus 스크랩 엔드포인트 공개.
                        // /actuator/prometheus 는 메트릭 텍스트만 노출하며 gateway 라우트에 없어 외부 미노출(클러스터 내부 Prometheus 가 스크랩, NetworkPolicy 로 격리 권장).
                        // /actuator/metrics(탐색형 단건 조회 API)는 그대로 인증 필요.
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/games/**").permitAll()
                        // 공개 카테고리 API
                        .requestMatchers(HttpMethod.GET, "/categories", "/categories/**").permitAll()
                        // 쿠폰 관련 API
                        .requestMatchers(HttpMethod.GET, "/coupons/available").hasAnyRole("ADMIN", "MANAGER", "USER")
                        .requestMatchers(HttpMethod.GET, "/coupons", "/coupons/**").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(HttpMethod.POST, "/coupons/*/use").hasAnyRole("ADMIN", "MANAGER", "USER")
                        // 전체 주문/사용자 조회 (관리자·매니저)
                        .requestMatchers("/orders/admin/all").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers("/orders/admin/**").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers("/users/admin/all").hasRole("ADMIN")
                        // 관리자 전용 카테고리 API
                        .requestMatchers("/admin/categories/**").hasRole("ADMIN")
                        // 운영자 전용 — settlement 프로젝션 백필 (Phase 4 Chunk 3)
                        .requestMatchers("/admin/settlement-projection/**").hasRole("ADMIN")
                        // 운영자 전용 — Outbox DLQ / Kafka DLT / PG 라우팅 / PG 정산파일 대사
                        .requestMatchers("/admin/outbox/**").hasRole("ADMIN")
                        .requestMatchers("/admin/dlq/**").hasRole("ADMIN")
                        // 소비 이벤트 3분류(정상·중복·격리) 추적 콘솔 + 격리 재처리 (P0-3)
                        .requestMatchers("/admin/event-track/**").hasRole("ADMIN")
                        .requestMatchers("/admin/pg/**").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers("/admin/reconciliation/**").hasAnyRole("ADMIN", "MANAGER")
                        // PG 정산파일 대사 콘솔 — 업로드·승인(역정산 트리거)·거절·조회. 경로가 /admin/pg/** 와
                        // 불일치해 authenticated() 로 새던 것을 형제 recon 콘솔과 동일하게 ADMIN/MANAGER 로 게이트.
                        .requestMatchers("/admin/pg-reconciliation/**").hasAnyRole("ADMIN", "MANAGER")
                        // 정합성 검증 콘솔 — 실행 없는 읽기 전용 조회라 MANAGER 도 허용 (Integrity Suite Phase A)
                        .requestMatchers("/admin/integrity/**").hasAnyRole("ADMIN", "MANAGER")
                        // 내부 서비스 간 호출 — order 가 자기 대사 합계를 노출(settlement 가 소비, ADR 0020 Phase 5 self-totals).
                        // gateway 미라우팅이지만 NodePort 직노출 대비 InternalApiKeyFilter 가 X-Internal-Api-Key 공유
                        // 시크릿을 검증(미설정 시 통과+경고). 여기선 permitAll 로 두고 게이팅은 필터가 담당. 운영선 NetworkPolicy/mTLS 추가 권장.
                        .requestMatchers("/internal/**").permitAll()
                        // Payout 콘솔 — 송금 권한은 ADMIN 만 (반송 기록·재지급 포함)
                        .requestMatchers("/admin/payouts/**").hasRole("ADMIN")
                        // 셀러 지급 계좌 레지스트리 — 등록·정정(PII). 셀러 식별자를 관리자 입력으로 받으므로
                        // ADMIN/MANAGER 게이트로 IDOR 방지 (Seed D1).
                        .requestMatchers("/admin/seller-bank-accounts/**").hasAnyRole("ADMIN", "MANAGER")
                        // 셀러 세무 프로필 레지스트리(PII 사업자등록번호) — 셀러 식별자를 관리자 입력으로 받으므로
                        // ADMIN/MANAGER 게이트로 IDOR 방지 (Seed B2, ADR 0027).
                        .requestMatchers("/admin/seller-tax-profiles/**").hasAnyRole("ADMIN", "MANAGER")
                        // 세무 산출물 운영 콘솔 — 세무 전표 전기·세금계산서 발행·3자 대사 (Seed B2).
                        .requestMatchers("/admin/tax/**").hasAnyRole("ADMIN", "MANAGER")
                        // 세금계산서 셀러 다운로드 — JWT 주체(userId) 파생 + 소유권 대조(403)로 IDOR 방지.
                        .requestMatchers("/api/tax-invoices/**").hasAnyRole("ADMIN", "MANAGER", "USER")
                        // Chargeback 콘솔 — 셀러 환수 결정은 ADMIN 만
                        .requestMatchers("/admin/chargebacks/**").hasRole("ADMIN")
                        // 백필 콘솔 — 원장 역분개·Payout 누락 보정 작업은 ADMIN 만
                        .requestMatchers("/admin/backfill/**").hasRole("ADMIN")
                        // 지급후 회수 채권·상계 조회 콘솔 — 읽기 전용이라 MANAGER 도 허용 (seed-p0-6)
                        .requestMatchers("/admin/recoveries/**").hasAnyRole("ADMIN", "MANAGER")
                        // 기업 신용대출 실행(실자금 지급) — 승인·실행 권한은 ADMIN 만.
                        // 신용평가 조회(/credit)·신청(POST /loans/corporate)·목록 조회는 인증 사용자(CEO) 허용.
                        .requestMatchers(HttpMethod.POST, "/loans/corporate/*/disburse").hasRole("ADMIN")
                        // 환불 콘솔 — 실패/재시도 소진 환불 조회(운영 개입용). 실행 없는 조회라 MANAGER 도 허용
                        .requestMatchers("/admin/refunds/**").hasAnyRole("ADMIN", "MANAGER")
                        // 정산 관련 API (관리자·매니저)
                        .requestMatchers("/settlements/**").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers("/api/settlements/**").hasAnyRole("ADMIN", "MANAGER")
                        // 재무/자금흐름 리포트 (관리자·매니저)
                        .requestMatchers("/api/reports/**").hasAnyRole("ADMIN", "MANAGER")
                        // 원장(Ledger) 조회 — 회계 감사용 (관리자·매니저)
                        .requestMatchers("/api/ledger/**").hasAnyRole("ADMIN", "MANAGER")
                        // 계정계(GL) 조회 콘솔 — owner 잔액·분개·전사 집계·시산표는 회계 백오피스라 관리자·매니저 전용
                        // (프론트도 /admin/ceo/accounts 를 AdminManagerRoute 로 보호). 무권한 노출(owner IDOR·전사 집계) 차단.
                        .requestMatchers("/api/account/**").hasAnyRole("ADMIN", "MANAGER")
                        // 결제 환불 이력 조회 (관리자·매니저·본인) — 더 세밀한 권한은 향후 Audit PR 에서
                        .requestMatchers("/api/payments/*/refunds").hasAnyRole("ADMIN", "MANAGER", "USER")
                        // 환불 실행(직접 PG 환불) — "어드민 승인 후 환불" 원칙에 따라 운영자 전용.
                        // 사용자 직접 호출 경로와 운영자 승인 경로를 분리한다(관리자 승인은 /orders/admin/{id}/refund-approve).
                        // 결제 생성/인증/캡처(/payments POST·/authorize·/capture)는 사용자 결제 흐름이라 제한하지 않는다.
                        .requestMatchers(HttpMethod.PATCH, "/payments/*/refund").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(HttpMethod.POST, "/payments/split/*/refund").hasAnyRole("ADMIN", "MANAGER")
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
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // 내부 API 공유 시크릿 필터 — JWT 보다 먼저 /internal/** 무자격 접근 차단
                .addFilterBefore(internalApiKeyFilter, JwtAuthenticationFilter.class);

        return http.build();
    }
}
