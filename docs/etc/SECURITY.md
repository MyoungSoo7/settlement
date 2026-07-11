# SECURITY (보안 가이드)

## 📋 개요
Lemuel 정산 시스템의 보안 아키텍처 및 보안 정책 문서

**버전**: 1.0.0
**최종 업데이트**: 2026-02-12

---

## 🔐 인증 (Authentication)

### JWT (JSON Web Token) 기반 인증

#### 토큰 구조
```json
{
  "iss": "lemuel-settlement-system",
  "sub": "user@example.com",
  "role": "USER|ADMIN",
  "iat": 1234567890,
  "exp": 1234654290
}
```

#### 토큰 생성
**파일**: `JwtUtil.java:23`

- **알고리즘**: HMAC-SHA256 (HS256)
- **서명 키**: 환경변수 `JWT_SECRET` (최소 32바이트 권장)
- **유효기간**: 86400초 (24시간)
- **발급자**: `JWT_ISSUER` 환경변수

```java
String token = Jwts.builder()
    .issuer(jwtProperties.getIssuer())
    .subject(email)
    .claim("role", role)
    .issuedAt(now)
    .expiration(expiration)
    .signWith(secretKey)
    .compact();
```

#### 토큰 검증
**파일**: `JwtUtil.java:46`

- 서명 검증
- 만료 시간 확인
- 발급자(issuer) 검증

---

### 비밀번호 암호화

#### BCrypt 해싱
**파일**: `SecurityConfig.java:30`

- **알고리즘**: BCrypt
- **라운드**: cost 12 (`new BCryptPasswordEncoder(12)`)
- **Salt**: 자동 생성

```java
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
}
```

#### 비밀번호 저장
**프로세스**:
1. 사용자가 평문 비밀번호 입력
2. BCryptPasswordEncoder가 자동으로 salt 생성
3. 비밀번호 + salt를 BCrypt로 해싱
4. 해시된 비밀번호를 DB에 저장

**⚠️ 주의**: 평문 비밀번호는 절대 로그에 기록하지 않음

---

## 🛡️ 인가 (Authorization)

### Role 기반 접근 제어 (RBAC)

#### 권한 레벨

| Role | 설명 | 접근 가능 리소스 |
|------|------|-----------------|
| `USER` | 일반 사용자 | 자신의 주문/결제/환불 조회, 정산 조회 |
| `ADMIN` | 관리자 | 모든 리소스 + 정산 승인/반려 |

#### Spring Security 설정
**파일**: `SecurityConfig.java:91`

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()  // API 문서
    .requestMatchers("/auth/login").permitAll()                        // 로그인
    .requestMatchers("/actuator/**").permitAll()                       // Health Check
    .requestMatchers("/users").permitAll()                             // 회원가입
    .anyRequest().authenticated()                                      // 나머지 인증 필요
)
```

#### 권한 체크
**파일**: `JwtAuthenticationFilter.java:40`

```java
UsernamePasswordAuthenticationToken authentication =
    new UsernamePasswordAuthenticationToken(
        email,
        null,
        List.of(new SimpleGrantedAuthority("ROLE_" + role))
    );
```

---

## 🔒 API 보안

### JWT 필터 체인
**파일**: `JwtAuthenticationFilter.java`

#### 요청 처리 흐름
```
HTTP 요청
  ↓
JwtAuthenticationFilter (Before UsernamePasswordAuthenticationFilter)
  ↓
Authorization 헤더 확인
  ├─ Bearer 토큰 있음 → 토큰 검증
  │   ├─ 유효함 → SecurityContext에 인증 정보 설정
  │   └─ 무효함 → 인증 정보 없음 (401)
  └─ 토큰 없음 → 인증 정보 없음 (401)
  ↓
다음 필터 체인 실행
  ↓
Controller 도달
  ↓
권한 체크 (ROLE_USER, ROLE_ADMIN)
```

#### 보호되지 않는 엔드포인트
- `POST /auth/login` - 로그인
- `POST /users` - 회원가입
- `GET /swagger-ui/**` - API 문서
- `GET /actuator/**` - Health Check

#### 보호되는 엔드포인트 (인증 필수)
- `GET /users/me` - 현재 사용자 정보
- `POST /orders` - 주문 생성
- `POST /payments` - 결제 생성
- `POST /payments/{id}/refund` - 환불 요청
- `GET /settlements/search` - 정산 검색
- `POST /settlements/{id}/approve` - 정산 승인 (ADMIN만)
- `POST /settlements/{id}/reject` - 정산 반려 (ADMIN만)

---

## 🌐 CORS (Cross-Origin Resource Sharing)

### CORS 정책
**파일**: `SecurityConfig.java:39`

#### 허용된 Origin
```java
"http://localhost:3000",      // React Dev Server
"http://localhost:5173",      // Vite Dev Server
"http://127.0.0.1:3000",
"http://127.0.0.1:5173"
```

#### 허용된 HTTP 메서드
- GET, POST, PUT, PATCH, DELETE, OPTIONS

#### 허용된 헤더
- `Authorization` - JWT 토큰
- `Content-Type` - 요청 본문 타입
- `X-Requested-With` - Ajax 요청 식별
- `Idempotency-Key` - 환불 API 멱등성 키

#### 노출된 응답 헤더
- `Authorization` - 토큰 갱신 시 사용
- `X-Total-Count` - 페이징 정보

#### 자격 증명 허용
```java
configuration.setAllowCredentials(true);  // 쿠키, Authorization 헤더 허용
```

---

## 🔓 CSRF (Cross-Site Request Forgery) 보호

### CSRF 비활성화
**파일**: `SecurityConfig.java:87`

```java
.csrf(csrf -> csrf.disable())
```

**이유**:
- JWT 토큰 기반 인증 사용
- Stateless 세션 (세션 쿠키 미사용)
- 모든 요청에 Authorization 헤더 필수
- Same-Origin 정책 위반 시 CORS로 차단

**⚠️ 주의**:
- 만약 쿠키 기반 인증으로 변경 시 CSRF 보호 반드시 활성화 필요
- API만 제공하는 서비스이므로 현재는 안전

---

## 🔑 세션 관리

### Stateless 세션
**파일**: `SecurityConfig.java:89`

```java
.sessionManagement(session ->
    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
)
```

**특징**:
- 서버에 세션 저장하지 않음
- JWT 토큰에 모든 인증 정보 포함
- 수평 확장(Scale-out)에 유리
- Redis 등 세션 저장소 불필요

---

## 🛡️ 보안 취약점 및 대응

### 1. SQL Injection
**위험도**: 🔴 높음

#### 현재 보호 수준: ✅ 안전
- **JPA/Hibernate** 사용으로 Prepared Statement 자동 생성
- **JPQL/Criteria API** 사용으로 파라미터 바인딩 자동 처리

#### 예시
```java
// ✅ 안전: JPA Query Method
User findByEmail(String email);

// ✅ 안전: JPQL with Parameter Binding
@Query("SELECT u FROM User u WHERE u.email = :email")
User findByEmailCustom(@Param("email") String email);

// ⚠️ 위험: Native Query (사용하지 않음)
// @Query(value = "SELECT * FROM users WHERE email = '" + email + "'", nativeQuery = true)
```

---

### 2. XSS (Cross-Site Scripting)
**위험도**: 🟡 중간

#### 현재 보호 수준: ⚠️ 부분적
- **프론트엔드**: React의 기본 XSS 방어 (JSX 자동 이스케이핑)
- **백엔드**: JSON 응답만 제공 (HTML 렌더링 없음)

#### 권장 사항
```javascript
// ✅ React는 기본적으로 안전
<div>{userInput}</div>  // 자동 이스케이핑

// ⚠️ 위험: dangerouslySetInnerHTML 사용 금지
// <div dangerouslySetInnerHTML={{__html: userInput}} />
```

#### 추가 보호
- 입력 검증: 이메일, 금액, 날짜 등 형식 검증
- 출력 이스케이핑: React가 자동 처리
- Content-Security-Policy 헤더 추가 권장

---

### 3. JWT 탈취 (Token Hijacking)
**위험도**: 🔴 높음

#### 현재 취약점: ⚠️ 주의 필요
- JWT를 **localStorage**에 저장 → XSS 공격 시 탈취 가능

#### 현재 완화 조치
- 토큰 유효기간: 24시간
- HTTPS 사용 (프로덕션 환경)

#### 권장 개선 사항

##### 1. HttpOnly 쿠키 사용 (권장)
```java
// 백엔드: JWT를 HttpOnly 쿠키로 전송
Cookie cookie = new Cookie("token", jwtToken);
cookie.setHttpOnly(true);     // JavaScript 접근 차단
cookie.setSecure(true);       // HTTPS만
cookie.setMaxAge(86400);      // 24시간
cookie.setPath("/");
cookie.setSameSite("Strict"); // CSRF 방어
response.addCookie(cookie);
```

##### 2. Refresh Token 도입
```
Access Token (Short-lived, 15분)
  +
Refresh Token (Long-lived, 7일, HttpOnly 쿠키)
```

##### 3. 토큰 블랙리스트 (로그아웃 시)
```
Redis에 만료된 토큰 저장
- Key: token
- Value: "revoked"
- TTL: 토큰 만료 시간까지
```

---

### 4. 무차별 대입 공격 (Brute Force)
**위험도**: 🟡 중간

#### 현재 보호 수준: ❌ 미보호

#### 권장 개선 사항

##### 1. Rate Limiting (요청 속도 제한)
```java
// Spring Boot Bucket4j 라이브러리 사용
@RateLimiter(name = "login")
@PostMapping("/auth/login")
public ResponseEntity<?> login(@RequestBody LoginRequest request) {
    // ...
}
```

##### 2. 계정 잠금 정책
```
- 5회 연속 실패 시 계정 잠금
- 잠금 시간: 15분
- 이메일 알림 발송
```

##### 3. CAPTCHA 추가
```
- 3회 실패 후 CAPTCHA 표시
- reCAPTCHA v3 권장
```

---

### 5. 권한 상승 (Privilege Escalation)
**위험도**: 🔴 높음

#### 현재 보호 수준: ⚠️ 부분적
- JWT에 role 정보 포함
- SecurityContext에서 권한 체크

#### 취약점
```java
// ❌ 취약: 사용자 입력을 신뢰
@PostMapping("/settlements/{id}/approve")
public ResponseEntity<?> approve(@PathVariable Long id,
                                 @RequestParam Long userId) {
    // userId를 파라미터로 받으면 조작 가능
}

// ✅ 안전: SecurityContext에서 추출
@PostMapping("/settlements/{id}/approve")
public ResponseEntity<?> approve(@PathVariable Long id,
                                 Authentication authentication) {
    String email = authentication.getName();
    User user = userRepository.findByEmail(email);
    // user.getId()를 사용
}
```

#### 권장 개선 사항
1. **메서드 레벨 보안**
```java
@PreAuthorize("hasRole('ADMIN')")
@PostMapping("/settlements/{id}/approve")
public ResponseEntity<?> approve(@PathVariable Long id) {
    // ...
}
```

2. **리소스 소유권 검증**
```java
// 사용자가 자신의 리소스만 접근하도록
Order order = orderRepository.findById(id);
if (!order.getUserId().equals(currentUser.getId())) {
    throw new AccessDeniedException("Not authorized");
}
```

---

### 6. 민감 정보 노출 (Sensitive Data Exposure)
**위험도**: 🔴 높음

#### 현재 보호 수준: ⚠️ 부분적

#### 취약점
- **로그**: 비밀번호, 토큰이 로그에 기록될 수 있음
- **에러 메시지**: 스택 트레이스가 클라이언트에 노출
- **환경 변수**: 평문으로 저장

#### 권장 개선 사항

##### 1. 로그 필터링
```java
// Logback 설정
<pattern>%d{yyyy-MM-dd HH:mm:ss} - %msg %replace(%xEx){'\bpassword=\S+', 'password=***'}%nopex%n</pattern>
```

##### 2. 에러 응답 일반화
```java
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleException(Exception e) {
        // ❌ 스택 트레이스 노출
        // return ResponseEntity.status(500).body(e.getMessage());

        // ✅ 일반화된 메시지
        log.error("Internal error", e);  // 서버 로그에만 기록
        return ResponseEntity.status(500).body("Internal server error");
    }
}
```

##### 3. 환경 변수 암호화
```bash
# Kubernetes Secret 사용
kubectl create secret generic lemuel-secrets \
  --from-literal=JWT_SECRET='your-secret-key' \
  --from-literal=POSTGRES_PASSWORD='db-password'
```

---

### 7. IDOR (Insecure Direct Object References)
**위험도**: 🔴 높음

#### 현재 보호 수준: ❌ 미보호

#### 취약점 예시
```java
// ❌ 취약: ID만으로 조회
@GetMapping("/orders/{id}")
public OrderResponse getOrder(@PathVariable Long id) {
    return orderRepository.findById(id);  // 다른 사용자 주문도 조회 가능
}

// ✅ 안전: 소유권 검증
@GetMapping("/orders/{id}")
public OrderResponse getOrder(@PathVariable Long id, Authentication auth) {
    String email = auth.getName();
    Order order = orderRepository.findById(id);
    if (!order.getUser().getEmail().equals(email)) {
        throw new AccessDeniedException("Not your order");
    }
    return toResponse(order);
}
```

#### 권장 개선 사항
1. **복합 조건 조회**
```java
Order findByIdAndUserId(Long id, Long userId);
```

2. **UUID 사용**
```java
// Sequential ID 대신 UUID 사용
@Id
@GeneratedValue(generator = "UUID")
@Column(columnDefinition = "uuid")
private UUID id;
```

---

### 8. 환불 중복 처리 (Idempotency)
**위험도**: 🟡 중간

#### 현재 보호 수준: ✅ 안전
**파일**: `Refund.java:28`

```java
@Column(name = "idempotency_key", nullable = false, unique = true)
private String idempotencyKey;
```

#### 동작 방식
```
요청 1: Idempotency-Key: abc123 → 환불 처리
요청 2: Idempotency-Key: abc123 → 중복 감지 (409 Conflict)
```

---

### 9. Mass Assignment
**위험도**: 🟡 중간

#### 현재 보호 수준: ✅ 안전
- DTO 패턴 사용으로 필드 제한

```java
// ✅ 안전: DTO로 허용된 필드만 받음
public class UserRegisterRequest {
    private String email;
    private String password;
    // role은 받지 않음 → 서버에서 "USER"로 고정
}

// ❌ 위험: Entity를 직접 받는 경우
// public ResponseEntity<?> register(@RequestBody User user) {
//     userRepository.save(user);  // role을 조작 가능
// }
```

---

### 10. API Rate Limiting
**위험도**: 🟡 중간

#### 현재 보호 수준: ✅ 구현됨 (Bucket4j)
- shared-common `common.ratelimit` 의 `RateLimitFilter`(Bucket4j) 가 전역 적용됨.

#### 추가 개선 여지 (참고)
```java
// (참고용) Resilience4j 기반 예시 — 실제 구현은 Bucket4j 필터
@RateLimiter(name = "default")
@GetMapping("/settlements/search")
public ResponseEntity<?> search() {
    // 분당 100회 제한
}
```

**설정 예시**:
```yaml
resilience4j:
  ratelimiter:
    instances:
      default:
        limit-for-period: 100
        limit-refresh-period: 1m
        timeout-duration: 0s
```

---

## 🔐 데이터베이스 보안

### 1. 연결 보안
**파일**: `application.yml:8`

```yaml
datasource:
  url: jdbc:postgresql://localhost:5432/opslab
  username: ${POSTGRES_USER}      # 환경변수
  password: ${POSTGRES_PASSWORD}  # 환경변수
```

#### 권장 사항
- ✅ 환경변수 사용 (하드코딩 금지)
- ✅ SSL/TLS 연결 사용 (프로덕션)
```yaml
url: jdbc:postgresql://localhost:5432/opslab?ssl=true&sslmode=require
```

### 2. 최소 권한 원칙
```sql
-- ❌ 위험: SUPERUSER 권한
CREATE USER lemuel_app WITH SUPERUSER PASSWORD 'password';

-- ✅ 안전: 최소 권한
CREATE USER lemuel_app WITH PASSWORD 'strong-password';
GRANT CONNECT ON DATABASE opslab TO lemuel_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA opslab TO lemuel_app;
```

### 3. 감사 로그 (Audit Log)
```sql
-- 중요 작업 로그 테이블
CREATE TABLE audit_logs (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    action VARCHAR(50) NOT NULL,  -- 'APPROVE', 'REJECT', 'REFUND'
    resource_type VARCHAR(50),    -- 'SETTLEMENT', 'PAYMENT'
    resource_id BIGINT,
    ip_address VARCHAR(45),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
```

---

## 🌐 네트워크 보안

### 1. HTTPS 강제 (TLS 1.2+)
**프로덕션 환경 필수**

```yaml
# application-prod.yml
server:
  ssl:
    enabled: true
    key-store: classpath:keystore.p12
    key-store-password: ${KEYSTORE_PASSWORD}
    key-store-type: PKCS12
    key-alias: lemuel
  port: 8443
```

### 2. HTTP → HTTPS 리다이렉트
```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) {
    http.requiresChannel(channel ->
        channel.anyRequest().requiresSecure()
    );
    return http.build();
}
```

### 3. 보안 헤더
```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) {
    http.headers(headers -> headers
        .contentSecurityPolicy(csp ->
            csp.policyDirectives("default-src 'self'")
        )
        .frameOptions(frame -> frame.deny())
        .xssProtection(xss -> xss.headerValue(XXssProtectionHeaderWriter.HeaderValue.ENABLED_MODE_BLOCK))
        .contentTypeOptions(Customizer.withDefaults())
    );
    return http.build();
}
```

**추가되는 헤더**:
- `Content-Security-Policy: default-src 'self'`
- `X-Frame-Options: DENY`
- `X-XSS-Protection: 1; mode=block`
- `X-Content-Type-Options: nosniff`
- `Strict-Transport-Security: max-age=31536000; includeSubDomains`

---

## 📊 모니터링 및 감사

### 1. 보안 이벤트 로깅
```java
@Slf4j
@Component
public class SecurityEventLogger {

    public void logLoginAttempt(String email, boolean success, String ip) {
        if (!success) {
            log.warn("Failed login attempt: email={}, ip={}", email, ip);
        } else {
            log.info("Successful login: email={}, ip={}", email, ip);
        }
    }

    public void logSettlementApproval(Long settlementId, Long adminId, String ip) {
        log.info("Settlement approved: id={}, admin={}, ip={}",
                 settlementId, adminId, ip);
    }
}
```

### 2. 의심스러운 활동 감지
```java
// 짧은 시간 내 여러 실패한 로그인 시도
@Component
public class LoginAttemptService {

    private final Map<String, Integer> attempts = new ConcurrentHashMap<>();

    public void loginFailed(String email) {
        attempts.merge(email, 1, Integer::sum);
        if (attempts.get(email) >= 5) {
            // 알림 발송
            alertService.sendAlert("Multiple failed login attempts: " + email);
        }
    }
}
```

### 3. Prometheus 메트릭
**파일**: `application.yml:88`

```yaml
management:
  metrics:
    tags:
      application: lemuel
      environment: production
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
```

**보안 관련 메트릭**:
- `http_server_requests_seconds` - 요청 응답 시간
- `authentication_failure_total` - 인증 실패 횟수
- `authorization_failure_total` - 인가 실패 횟수

---

## 🚨 인시던트 대응

### 1. JWT 토큰 유출 시
1. **즉시 조치**:
   - `JWT_SECRET` 환경변수 변경
   - 애플리케이션 재시작 (모든 토큰 무효화)
2. **장기 조치**:
   - Refresh Token 블랙리스트 구현
   - 토큰 유효기간 단축 (24시간 → 15분)

### 2. 데이터베이스 침해 시
1. **즉시 조치**:
   - DB 연결 차단
   - 침해 범위 파악
2. **복구**:
   - 백업에서 복원
   - 비밀번호 전체 재설정 강제
3. **사후 조치**:
   - 보안 감사 수행
   - 접근 제어 강화

### 3. DDoS 공격 시
1. **즉시 조치**:
   - Rate Limiting 활성화
   - CloudFlare/AWS Shield 활성화
2. **완화**:
   - IP 블랙리스트 적용
   - 트래픽 패턴 분석

---

## ✅ 보안 체크리스트

### 배포 전 필수 확인 사항

#### 인증/인가
- [ ] JWT Secret이 강력한가? (최소 32바이트, 무작위)
- [ ] JWT 만료 시간이 적절한가? (권장: 15분 ~ 1시간)
- [ ] 비밀번호가 BCrypt로 해싱되는가?
- [ ] ADMIN 권한이 제대로 체크되는가?

#### API 보안
- [ ] 모든 민감한 엔드포인트에 인증이 필요한가?
- [ ] CORS 설정이 프로덕션 도메인만 허용하는가?
- [ ] Rate Limiting이 설정되었는가?
- [ ] API 문서가 프로덕션에 노출되지 않는가?

#### 데이터베이스
- [ ] DB 비밀번호가 환경변수로 관리되는가?
- [ ] DB 사용자 권한이 최소화되었는가?
- [ ] SSL/TLS 연결이 활성화되었는가?
- [ ] 백업이 암호화되어 저장되는가?

#### 네트워크
- [ ] HTTPS가 활성화되었는가?
- [ ] HTTP가 HTTPS로 리다이렉트되는가?
- [ ] 보안 헤더가 설정되었는가?
- [ ] 불필요한 포트가 방화벽으로 차단되었는가?

#### 로깅/모니터링
- [ ] 민감 정보가 로그에 기록되지 않는가?
- [ ] 보안 이벤트가 로깅되는가?
- [ ] 의심스러운 활동 알림이 설정되었는가?
- [ ] 로그가 안전하게 저장되는가?

#### 코드 레벨
- [ ] SQL Injection 방어가 되어있는가?
- [ ] XSS 방어가 되어있는가?
- [ ] CSRF 토큰이 필요한 경우 적용되었는가?
- [ ] 사용자 입력이 검증되는가?
- [ ] 에러 메시지가 일반화되었는가?

---

## 🔧 보안 강화 로드맵

### Phase 1: 필수 (즉시)
1. ✅ JWT 기반 인증 구현
2. ✅ BCrypt 비밀번호 해싱
3. ✅ CORS 설정
4. ⚠️ HTTPS 활성화 (프로덕션)
5. ⚠️ 환경변수 암호화 (Kubernetes Secret)

### Phase 2: 중요 (1개월 내)
1. ✅ Rate Limiting 구현 (Bucket4j — shared-common `common.ratelimit`)
2. ❌ HttpOnly 쿠키로 토큰 전달
3. ❌ Refresh Token 도입
4. ❌ 계정 잠금 정책
5. ❌ 보안 헤더 추가

### Phase 3: 권장 (3개월 내)
1. ❌ 메서드 레벨 보안 (@PreAuthorize)
2. ❌ 감사 로그 시스템
3. ❌ 의심스러운 활동 감지
4. ❌ 침입 탐지 시스템 (IDS)
5. ❌ 정기 보안 감사

---

## 📚 참고 자료

### OWASP Top 10 (2021)
1. Broken Access Control
2. Cryptographic Failures
3. Injection
4. Insecure Design
5. Security Misconfiguration
6. Vulnerable and Outdated Components
7. Identification and Authentication Failures
8. Software and Data Integrity Failures
9. Security Logging and Monitoring Failures
10. Server-Side Request Forgery (SSRF)

### 관련 표준
- **PCI DSS**: 결제 카드 산업 데이터 보안 표준
- **ISO 27001**: 정보 보안 관리 시스템
- **GDPR**: 유럽 개인정보보호법

---

## 📞 보안 문의

보안 취약점 발견 시:
- **이메일**: security@lemuel.example.com
- **대응 시간**: 24시간 이내
- **공개 정책**: 책임 있는 공개 (Responsible Disclosure)

---

**작성일**: 2026-02-12
**버전**: 1.0.0
**다음 검토 예정**: 2026-03-12
