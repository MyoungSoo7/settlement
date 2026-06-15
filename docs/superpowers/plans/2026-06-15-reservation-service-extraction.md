# reservation-service 추출 구현 계획 (Phase A → Phase B)

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `order-service` 안에 섞여 있는 `reservation`(시공예약/기사배정) 도메인을, 먼저 코드 경계 0짜리 독립 Gradle 모듈로 추출(Phase A)하고, 이어서 자체 배포·자체 DB를 가진 진짜 Database-per-Service(Phase B)로 분리한다.

**Architecture:** 헥사고날 구조를 유지한 채 `reservation` 패키지를 `reservation-service` 모듈로 옮긴다. `order-service`의 `user` 도메인에 대한 코드 의존(현재 9개 import)을 두 갈래로 끊는다 — (1) 컨트롤러의 인증/권한·요청자 식별은 **JWT claim(role + userId)** 으로, (2) 기사 배정 검증(`verifyAssignableTechnician`)은 **포트(`ReservationTechnicianPort`)** 로 추상화한다. Phase A에서는 포트를 order-service 조립 루트의 임시 in-process 어댑터(`LoadUserPort` 사용)로 구현하고, Phase B에서 이를 **Kafka 이벤트로 동기화되는 로컬 기사 프로젝션** 어댑터로 교체하면서 교차 DB FK를 제거하고 DB를 물리 분리한다.

**Tech Stack:** Java 25, Spring Boot 4.0.4, Gradle multi-module (Kotlin DSL), PostgreSQL 17, Flyway, Kafka(Redpanda), Spring Security(JWT HS256, shared-common), JUnit5 + Testcontainers + ArchUnit.

---

## 설계 결정 (실행 전 반드시 숙지)

1. **패키지명은 끝까지 `github.lms.lemuel.reservation` 으로 유지한다.** 모듈만 바뀌고 FQCN은 안 바뀌므로 `LemuelApplication`의 `scanBasePackages` 와 import가 그대로 동작한다. Phase A의 위험을 최소화하는 핵심.
2. **Phase A 종착점 = 동작하는 단일 배포.** `reservation-service`는 settlement-service와 동일한 *라이브러리 모드*(`bootJar` 비활성)로 만들고 `order-service`가 번들한다. 배포 1개·DB 1개(opslab) 유지. 이 시점에서 reservation 모듈의 user/order/product 코드 import = 0.
3. **Phase B 종착점 = 독립 배포 + 독립 DB.** 자체 `@SpringBootApplication`, 자체 datasource(`reservations_db`), gateway 라우팅, 이벤트 기반 기사 프로젝션, 교차 FK 제거.
4. **기사 프로젝션의 타이밍:** 사용자 선택은 "JWT claims + 이벤트 동기화 로컬 기사 프로젝션"이다. 권한/요청자 식별(JWT)은 Phase A에서 즉시 적용한다. 기사 *프로젝션*은 DB가 실제로 갈라지는 Phase B에서 도입한다(단일 DB 상태에서 프로젝션 테이블은 가치가 낮고 복잡도만 높기 때문). Phase A에서는 동일 포트를 in-process 어댑터로 임시 충족한다. 이 단계 구분이 의도된 설계다.
5. **커밋은 잦게.** 각 Task 끝 컴파일·테스트 통과 시 즉시 커밋. Phase 경계에서 반드시 전체 빌드 그린 확인.

## 사전 점검 (Task 시작 전 1회)

- [ ] 현재 브랜치 확인: `git branch --show-current` (작업 브랜치 `assign` 위에서 진행하거나 `feat/reservation-extraction` 분기)
- [ ] 베이스라인 그린 확인: `./gradlew :order-service:test` → 통과해야 함. 실패 시 먼저 원인 격리(이 계획 범위 밖).
- [ ] reservation 관련 기존 테스트 위치 확인:
  - Run: `git ls-files "order-service/src/test/**reservation**"`
  - 결과(파일 목록)를 기록. Phase A에서 이 테스트들도 함께 이동한다.

---

## Chunk 1: Phase A — 모듈 골격 + 소스 이동

### Task A1: `reservation-service` Gradle 모듈 생성 (라이브러리 모드)

**Files:**
- Modify: `settings.gradle.kts:7-12`
- Create: `reservation-service/build.gradle.kts`
- Modify: `order-service/build.gradle.kts:8-12` (의존 추가)

- [ ] **Step 1: settings.gradle.kts 에 모듈 등록**

`settings.gradle.kts` 의 `include(...)` 를 다음으로 교체:

```kotlin
include(
    "shared-common",
    "order-service",
    "settlement-service",
    "reservation-service",
    "gateway-service",
)
```

- [ ] **Step 2: reservation-service/build.gradle.kts 작성**

`settlement-service/build.gradle.kts` 를 템플릿으로, reservation 에 불필요한 의존(Elasticsearch·Batch·iText)을 제거한 최소본. 라이브러리 모드(`bootJar` 비활성)는 Phase A 필수, Phase B에서 되돌린다.

```kotlin
plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

// Phase A: 라이브러리 모드 — order-service fat jar 에 번들된다.
// Phase B(Task B1)에서 bootJar 활성 + 자체 main 클래스로 전환.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = false
}
tasks.named<Jar>("jar") {
    enabled = true
    archiveClassifier.set("")
}

dependencies {
    implementation(project(":shared-common"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jackson")

    // Flyway
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-flyway")

    // Kafka (Phase B: 기사 프로젝션 이벤트 consume)
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.kafka:spring-kafka")

    // SpringDoc OpenAPI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2")

    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql:42.7.3")
    implementation("io.github.cdimascio:java-dotenv:5.2.2")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    developmentOnly("org.springframework.boot:spring-boot-devtools")
    testImplementation("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
}

val mockitoAgent = configurations.create("mockitoAgent")
dependencies {
    mockitoAgent("org.mockito:mockito-core") { isTransitive = false }
}
tasks.named<Test>("test") {
    useJUnitPlatform()
    jvmArgs("-javaagent:${mockitoAgent.asPath}")
}
```

> 주의: `reservation` 도메인은 MapStruct/QueryDSL을 쓰지 않는 것으로 보인다(Mapper는 수기). A2 이동 후 컴파일 에러가 나면 그때 querydsl/mapstruct 블록을 settlement 빌드에서 그대로 가져와 추가한다.

- [ ] **Step 3: order-service 가 reservation-service 를 번들하도록 의존 추가**

`order-service/build.gradle.kts` 의 의존 블록(현재 `implementation(project(":settlement-service"))` 바로 아래)에 추가:

```kotlin
    // 임시 모놀리식 번들 — Phase A. Phase B(Task B1)에서 제거.
    implementation(project(":reservation-service"))
```

- [ ] **Step 4: 빈 모듈이 인식되는지 확인**

Run: `./gradlew :reservation-service:compileJava`
Expected: `BUILD SUCCESSFUL` (소스 0개라도 성공)

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts reservation-service/build.gradle.kts order-service/build.gradle.kts
git commit -m "build(reservation): scaffold reservation-service library module"
```

---

### Task A2: reservation 소스·테스트를 새 모듈로 이동 (FQCN 유지)

**Files:**
- Move: `order-service/src/main/java/github/lms/lemuel/reservation/**` → `reservation-service/src/main/java/github/lms/lemuel/reservation/**`
- Move: 사전 점검에서 기록한 `order-service/src/test/**reservation**` → `reservation-service/src/test/...` (동일 상대경로)

- [ ] **Step 1: 메인 소스 디렉터리 통째 이동 (git mv 로 이력 보존)**

```bash
mkdir -p reservation-service/src/main/java/github/lms/lemuel
git mv order-service/src/main/java/github/lms/lemuel/reservation \
       reservation-service/src/main/java/github/lms/lemuel/reservation
```

- [ ] **Step 2: 테스트 소스 이동**

사전 점검의 파일 목록 기준으로 각 파일을 동일 패키지 경로로 이동(`git mv`). 디렉터리째 옮길 수 있으면:

```bash
mkdir -p reservation-service/src/test/java/github/lms/lemuel
git mv order-service/src/test/java/github/lms/lemuel/reservation \
       reservation-service/src/test/java/github/lms/lemuel/reservation
```

(테스트가 다른 위치에 흩어져 있으면 개별 `git mv`. 통합테스트 부트스트랩이 order-service의 테스트 설정에 의존하면 A6에서 조정.)

- [ ] **Step 3: 컴파일 — user 의존 때문에 실패하는 것이 정상**

Run: `./gradlew :reservation-service:compileJava`
Expected: **FAIL** — `package github.lms.lemuel.user... does not exist` (ChangeReservationStatusService, ReservationController 두 곳). 이 실패가 A3/A4에서 끊을 대상을 정확히 가리킨다. 그 외 import 에러가 나오면 추가 결합이 있다는 뜻이므로 기록 후 동일 방식(포트화)으로 처리.

- [ ] **Step 4: Commit (의도적으로 빨간 상태 — 이동만 원자 커밋)**

```bash
git add -A
git commit -m "refactor(reservation): move reservation package to reservation-service module (compiles after A3/A4)"
```

---

## Chunk 2: Phase A — user 코드 의존 절단

### Task A3: 컨트롤러 인증/권한·요청자 식별을 JWT claim 으로 전환

현재 `ReservationController` 는 `SecurityContext` 의 email 로 `LoadUserPort.findByEmail` 을 호출해 `User` 를 재조회하고 `getRole()`/`getId()` 를 쓴다. JWT에는 이미 `role` claim 과 `ROLE_*` 권한이 들어 있다(`JwtUtil.generateToken`, `JwtAuthenticationFilter`). 빠진 것은 **`userId`** 뿐이다.

**Files:**
- Modify: `shared-common/src/main/java/github/lms/lemuel/common/config/jwt/JwtUtil.java`
- Modify: `shared-common/src/main/java/github/lms/lemuel/common/config/jwt/JwtAuthenticationFilter.java`
- Find & Modify: `JwtUtil.generateToken(...)` 호출부(로그인 서비스) — 토큰 발급 시 userId 전달
- Modify: `reservation-service/.../reservation/adapter/in/web/ReservationController.java`
- Test: `shared-common/src/test/.../jwt/JwtUtilTest.java`, `reservation-service/.../ReservationControllerTest`(있으면)

- [ ] **Step 1: 실패 테스트 — 토큰에 userId claim 이 담긴다**

`shared-common` 테스트에 추가(파일 없으면 생성):

```java
@Test
void generateToken_includesUserIdClaim() {
    JwtProperties props = new JwtProperties();
    props.setSecret("0123456789abcdef0123456789abcdef"); // 32B
    props.setIssuer("lemuel");
    props.setTtlSeconds(3600);
    JwtUtil jwtUtil = new JwtUtil(props);

    String token = jwtUtil.generateToken("tech@lemuel.io", "TECHNICIAN", 42L);

    io.jsonwebtoken.Claims claims = jwtUtil.parseToken(token);
    assertThat(claims.get("uid", Long.class)).isEqualTo(42L);
    assertThat(claims.get("role", String.class)).isEqualTo("TECHNICIAN");
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :shared-common:test --tests "*JwtUtilTest*"`
Expected: FAIL — `generateToken(String,String,long)` 메서드 없음(컴파일 에러).

- [ ] **Step 3: JwtUtil 에 userId claim 추가 (하위호환 오버로드 유지)**

`generateToken(String email, String role)` 를 다음으로 확장. 기존 2-인자 호출부를 깨지 않도록 오버로드로 둔다:

```java
public String generateToken(String email, String role) {
    return generateToken(email, role, null);
}

public String generateToken(String email, String role, Long userId) {
    long nowMillis = System.currentTimeMillis();
    Date now = new Date(nowMillis);
    Date expiration = new Date(nowMillis + (jwtProperties.getTtlSeconds() * 1000));

    var builder = Jwts.builder()
            .issuer(jwtProperties.getIssuer())
            .subject(email)
            .claim("role", role)
            .issuedAt(now)
            .expiration(expiration);
    if (userId != null) {
        builder.claim("uid", userId);
    }
    return builder.signWith(secretKey).compact();
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :shared-common:test --tests "*JwtUtilTest*"`
Expected: PASS

- [ ] **Step 5: 로그인 발급부에서 userId 전달**

토큰 발급 호출부를 찾는다:
Run: `git grep -n "generateToken(" -- order-service/src/main`
해당 로그인/인증 서비스에서 인증된 `User` 의 `getId()` 를 3번째 인자로 전달하도록 수정. (예: `jwtUtil.generateToken(user.getEmail(), user.getRole().name(), user.getId())`)

- [ ] **Step 6: 인증 principal 에 userId 노출**

`JwtAuthenticationFilter` 에서 principal 을 email 문자열 대신 userId/role 을 함께 담는 객체로 교체. shared-common 에 작은 record 추가:

`shared-common/src/main/java/github/lms/lemuel/common/config/jwt/AuthPrincipal.java`

**⚠ 하위호환이 필수다.** 코드 전수조사 결과 `authentication.getPrincipal()` 은 **어디서도 호출되지 않고**, 인증 주체 접근은 전부 `authentication.getName()`(현재 = email 문자열)에 의존한다(order-service 의 user/order/product/category/reservation 컨트롤러·서비스 다수). principal 을 String→객체로 바꾸면 `AbstractAuthenticationToken.getName()` 이 `principal.toString()` 을 반환하므로, **`AuthPrincipal` 이 `getName()`/`toString()` 에서 email 을 돌려주지 않으면 그 모든 호출처가 한꺼번에 깨진다.** 따라서 `AuthenticatedPrincipal` 을 구현해 `getName()=email` 을 보장한다:

```java
package github.lms.lemuel.common.config.jwt;

import org.springframework.security.core.AuthenticatedPrincipal;

/** SecurityContext authentication.getPrincipal() 로 노출되는 인증 주체.
 *  getName()/toString() 은 email 을 반환해 기존 authentication.getName() 호출처와 100% 하위호환. */
public record AuthPrincipal(Long userId, String email, String role) implements AuthenticatedPrincipal {
    @Override public String getName() { return email; }   // ★ getName()=email 유지가 회귀 방지의 핵심
    @Override public String toString() { return email; }
}
```

`JwtAuthenticationFilter.doFilterInternal` 의 토큰 파싱부 수정:
```java
Claims claims = jwtUtil.parseToken(token);
String email = claims.getSubject();
String role = claims.get("role", String.class);
Long uid = claims.get("uid", Long.class); // 구 토큰 호환: null 가능

UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(uid, email, role),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
```

> **회귀 검증(필수):** `AuthenticatedPrincipal` 구현으로 `getName()=email` 이 유지되면 기존 호출처는 코드 변경 없이 그대로 동작한다(이것이 의도된 무변경 경로). 그래도 폭발 반경이 넓으므로 변경 후 `./gradlew :order-service:test` **전체**를 돌려 회귀 0 을 확인한다. reservation 컨트롤러만 새 능력(userId)을 쓰려고 `(AuthPrincipal) auth.getPrincipal()` 로 캐스팅한다. 만약 `getName()=email` 호환을 두지 않고 principal 캐스팅 방식으로 전면 전환하려면, 위 18개 후보 파일 중 *인증* 용도의 `getName()`(email) 호출만 선별해 모두 고쳐야 하므로 비권장.

- [ ] **Step 7: ReservationController 의 user 의존 제거**

`import github.lms.lemuel.user.*` 5줄을 전부 삭제. `LoadUserPort` 필드 제거. `requireAuthenticated/requireAdmin/requireCompany/requireTechnician` 를 principal 기반 헬퍼로 교체:

```java
private AuthPrincipal currentUser() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof AuthPrincipal p) || p.userId() == null) {
        throw new InvalidCredentialsException("Authentication required");
    }
    return p;
}
private void require(AuthPrincipal p, String... roles) {
    for (String r : roles) if (r.equals(p.role())) return;
    throw new InvalidCredentialsException("Forbidden role: " + p.role());
}
```

- `register`: `currentUser()` 로 받고 `require(p, "COMPANY","ADMIN")`, `p.userId()` 를 companyId 로 사용.
- `getMine`: `require(p,"COMPANY","ADMIN")`, `p.userId()`.
- `getMyAssignments`: `require(p,"TECHNICIAN","ADMIN")`, `p.userId()`.
- `dashboard`/상태전이: `require(p,"ADMIN","MANAGER")`.
- `cancel`: ADMIN 이 아니면 `p.role().equals("COMPANY") && existing.getCompanyId().equals(p.userId())` 검증.

`InvalidCredentialsException` 는 user 패키지 소속이므로, reservation 자체 예외(`reservation.domain.exception` 에 `ForbiddenReservationAccessException` 신설)로 대체하고 `ReservationExceptionHandler` 에 403 매핑 추가. (user 예외에 의존하면 경계가 다시 생긴다.)

- [ ] **Step 8: 컴파일 — 이제 컨트롤러는 user 미참조**

Run: `./gradlew :reservation-service:compileJava`
Expected: ChangeReservationStatusService 의 user import 만 남아 FAIL (A4 대상). 컨트롤러발 user 에러는 사라져야 함.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(auth): carry userId in JWT claim; sever reservation controller from user domain"
```

---

### Task A4: 기사 배정 검증을 포트로 추상화 + Phase A 임시 어댑터

`ChangeReservationStatusService.verifyAssignableTechnician` 는 *배정 대상 기사*(다른 사용자)의 `role==TECHNICIAN` 과 `canUseService()`(membership APPROVED)를 확인한다. 이는 요청자 토큰으로 알 수 없는 참조 데이터다. 포트로 추상화한다.

**Files:**
- Create: `reservation-service/.../reservation/application/port/out/ReservationTechnicianPort.java`
- Modify: `reservation-service/.../reservation/application/service/ChangeReservationStatusService.java`
- Create: `order-service/.../reservation_bridge/UserBackedTechnicianAdapter.java` (조립 루트 임시 어댑터 — order-service 에 둔다)
- Test: `reservation-service/.../ChangeReservationStatusServiceTest.java`

- [ ] **Step 1: 포트 정의**

```java
package github.lms.lemuel.reservation.application.port.out;

/** 배정 대상 시공기사의 자격을 검증하기 위한 아웃바운드 포트.
 *  Phase A: order-service 의 in-process 어댑터. Phase B: 로컬 기사 프로젝션 어댑터. */
public interface ReservationTechnicianPort {
    /** 해당 userId 가 활성(APPROVED) 상태의 TECHNICIAN 이면 true. 존재하지 않으면 false. */
    boolean isAssignableTechnician(Long technicianId);
}
```

- [ ] **Step 2: 실패 테스트 — 서비스가 포트로 기사 검증한다**

`ChangeReservationStatusServiceTest` 에 추가/수정 (포트를 mock):
```java
@Test
void assign_rejects_whenNotAssignableTechnician() {
    when(technicianPort.isAssignableTechnician(99L)).thenReturn(false);
    assertThatThrownBy(() -> service.assign(1L, 99L))
        .isInstanceOf(IllegalArgumentException.class);
    verify(technicianPort).isAssignableTechnician(99L);
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew :reservation-service:test --tests "*ChangeReservationStatusServiceTest*"`
Expected: FAIL (컴파일 — `technicianPort` 없음 / 생성자 시그니처 불일치)

- [ ] **Step 4: 서비스에서 user import 제거하고 포트 사용**

`import github.lms.lemuel.user.*` 4줄 삭제. 생성자에 `ReservationTechnicianPort technicianPort` 주입. `verifyAssignableTechnician` 교체:
```java
private void verifyAssignableTechnician(Long technicianId) {
    if (technicianId == null) throw new IllegalArgumentException("technicianId is required");
    if (!technicianPort.isAssignableTechnician(technicianId)) {
        throw new IllegalArgumentException("Assignee must be an active TECHNICIAN: userId=" + technicianId);
    }
}
```
`UserNotFoundException` 의존도 제거(포트가 false 반환으로 흡수).

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :reservation-service:test --tests "*ChangeReservationStatusServiceTest*"`
Expected: PASS

- [ ] **Step 6: 모듈 단독 컴파일 — user 의존 완전 소멸**

Run: `./gradlew :reservation-service:compileJava`
Expected: **BUILD SUCCESSFUL** (reservation 모듈에 user/order/product import 0)

검증: `git grep -n "import github.lms.lemuel.\(user\|order\|payment\|product\|cart\|shipping\)" -- reservation-service/src/main` → **결과 0줄**이어야 함.

- [ ] **Step 7: Phase A 임시 어댑터를 order-service 조립 루트에 작성**

reservation 모듈 밖(order-service)에서 user 접근은 합법. 새 패키지 `github.lms.lemuel.reservation_bridge`:
```java
package github.lms.lemuel.reservation_bridge;

import github.lms.lemuel.reservation.application.port.out.ReservationTechnicianPort;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.domain.UserRole;
import org.springframework.stereotype.Component;

/** Phase A 전용 in-process 어댑터. Phase B(Task B4)에서 이벤트 프로젝션 어댑터로 교체·삭제. */
@Component
public class UserBackedTechnicianAdapter implements ReservationTechnicianPort {
    private final LoadUserPort loadUserPort;
    public UserBackedTechnicianAdapter(LoadUserPort loadUserPort) { this.loadUserPort = loadUserPort; }

    @Override
    public boolean isAssignableTechnician(Long technicianId) {
        return loadUserPort.findById(technicianId)
            .map(u -> u.getRole() == UserRole.TECHNICIAN && u.canUseService())
            .orElse(false);
    }
}
```
`LemuelApplication.scanBasePackages` 에 `"github.lms.lemuel.reservation_bridge"` 추가.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(reservation): abstract technician validation behind port; Phase A in-process adapter"
```

---

### Task A5: ArchUnit 으로 경계 고정 + Phase A 전체 그린

**Files:**
- Create: `reservation-service/src/test/.../reservation/ReservationArchitectureTest.java`

- [ ] **Step 1: 경계 위반 시 실패하는 ArchUnit 규칙**

```java
@AnalyzeClasses(packages = "github.lms.lemuel.reservation")
class ReservationArchitectureTest {
    @ArchTest
    static final ArchRule no_dependency_on_other_business_domains =
        noClasses().that().resideInAPackage("github.lms.lemuel.reservation..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "github.lms.lemuel.user..",
                "github.lms.lemuel.order..",
                "github.lms.lemuel.payment..",
                "github.lms.lemuel.product..",
                "github.lms.lemuel.cart..",
                "github.lms.lemuel.shipping..");
}
```

- [ ] **Step 2: 규칙 통과 확인**

Run: `./gradlew :reservation-service:test --tests "*ReservationArchitectureTest*"`
Expected: PASS (A3·A4 이후 의존 0)

- [ ] **Step 3: 전체 빌드 그린 — Phase A 완료 게이트**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. 특히 `:order-service:test`(A3 principal 변경 회귀)와 `:reservation-service:test` 모두 통과.

- [ ] **Step 4: 앱 부팅 스모크 — reservation 기능이 단일 배포에서 동작**

Run: `./gradlew :order-service:bootRun` (별도 터미널) 후 예약 등록→확인→기사배정 happy-path 1회 수동 호출 또는 기존 통합테스트로 대체.
Expected: 200/201 정상, 기사배정 시 비-기사 userId 는 400.

- [ ] **Step 5: Commit (Phase A 종료)**

```bash
git add -A
git commit -m "test(reservation): enforce module boundary via ArchUnit; Phase A complete"
```

> **Phase A 종료 상태:** reservation 은 독립 모듈, 코드 의존 0, 단일 배포·단일 DB에서 정상 동작. 여기서 멈춰도 "코드 경계 MSA"는 달성. 아래 Phase B는 배포·DB 물리 분리.

---

## Chunk 3: Phase B — 독립 배포 전환

### Task B1: 자체 `@SpringBootApplication` + bootJar 활성

**Files:**
- Create: `reservation-service/.../ReservationServiceApplication.java`
- Modify: `reservation-service/build.gradle.kts` (bootJar 활성)
- Modify: `order-service/build.gradle.kts` (reservation 의존 제거)
- Modify: `order-service/.../LemuelApplication.java` (reservation·reservation_bridge 스캔 제거)
- Move: `order-service/.../reservation_bridge/**` → Phase B 어댑터로 대체(Task B4에서 삭제)

- [ ] **Step 1: 자체 부트 클래스**

```java
package github.lms.lemuel.reservation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
    "github.lms.lemuel.reservation",
    "github.lms.lemuel.common",   // JWT 필터·SecurityConfig·Outbox 공용
})
public class ReservationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReservationServiceApplication.class, args);
    }
}
```

- [ ] **Step 2: build.gradle.kts 라이브러리 모드 해제**

reservation-service/build.gradle.kts 의 `bootJar { enabled = false }` / `jar { ... }` 블록 삭제(또는 bootJar enabled=true).

- [ ] **Step 3: order-service 에서 reservation 분리**

- `order-service/build.gradle.kts` 에서 `implementation(project(":reservation-service"))` 줄 삭제.
- `LemuelApplication.scanBasePackages` 에서 `"github.lms.lemuel.reservation"` 과 `"github.lms.lemuel.reservation_bridge"` 제거.

- [ ] **Step 4: 두 모듈 각각 컴파일**

Run: `./gradlew :order-service:compileJava :reservation-service:compileJava`
Expected: 둘 다 성공. (order-service 에 reservation 미참조 잔재가 있으면 에러 → 제거)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(reservation): standalone Spring Boot application; unbundle from order-service"
```

---

### Task B2: 자체 데이터소스 + Flyway 위치 분리 + 마이그레이션 이관

**Files:**
- Create: `reservation-service/src/main/resources/application.yml`
- Create: `reservation-service/src/main/resources/db/migration/` (이관)
- Move: `order-service/.../db/migration/V20260610090100__reservations.sql`, `V20260610090200__reservation_technician_assignment.sql` → reservation-service

- [ ] **Step 1: reservation-service application.yml**

`order-service/application.yml` 의 datasource/jpa/flyway/kafka 블록을 축약 이식. 핵심:
```yaml
server:
  port: 8083
spring:
  application:
    name: reservation-service
  datasource:
    url: jdbc:postgresql://localhost:5432/reservations_db
    username: ${DB_USER}
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
    locations: classpath:db/migration
    schemas: reservation
app:
  kafka:
    enabled: ${APP_KAFKA_ENABLED:true}
    topic:
      user-membership-changed: lemuel.user.membership-changed
jwt:
  secret: ${JWT_SECRET}
  issuer: lemuel
  ttl-seconds: 3600
```

- [ ] **Step 2: 마이그레이션 파일 이관 + 스키마명 일반화**

```bash
mkdir -p reservation-service/src/main/resources/db/migration
git mv order-service/src/main/resources/db/migration/V20260610090100__reservations.sql \
       reservation-service/src/main/resources/db/migration/V1__reservations.sql
git mv order-service/src/main/resources/db/migration/V20260610090200__reservation_technician_assignment.sql \
       reservation-service/src/main/resources/db/migration/V2__reservation_technician_assignment.sql
```
두 파일 내 `opslab.` 스키마 접두어를 `reservation.` 로 치환(또는 schema 설정에 맡기고 접두어 제거). **단, FK 라인은 B3에서 제거하므로 일단 두지 말 것** — 아래 B3 Step 1에서 즉시 처리.

- [ ] **Step 3: 새 DB 생성 + 마이그레이션 검증 (Testcontainers)**

settlement-integration-test 스킬 패턴으로 reservation-service 통합테스트 부트스트랩 작성, Testcontainers PostgreSQL 로 Flyway 가 `reservations_db`/`reservation` 스키마에 깨끗이 적용되는지 확인.
Run: `./gradlew :reservation-service:test --tests "*ReservationMigration*"`
Expected: PASS (Flyway clean migrate 성공)

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(reservation): own datasource (reservations_db) and migrations"
```

---

### Task B3: 교차 DB FK 제거 (분리의 필수 조건)

`V20260610090200` 의 `fk_reservations_technician FOREIGN KEY (technician_id) REFERENCES opslab.users(id)` 는 DB가 갈라지면 존재할 수 없다. 제거하고 무결성은 애플리케이션(기사 프로젝션)으로 옮긴다.

**Files:**
- Modify: 이관된 `reservation-service/.../V2__reservation_technician_assignment.sql`
- (운영 DB가 이미 FK를 가진 경우) Create: `reservation-service/.../db/migration/V3__drop_user_fk.sql`

- [ ] **Step 1: 신규 환경 — V2 에서 FK 구문 삭제**

이관한 `V2__...sql` 에서 다음 블록 삭제:
```sql
ALTER TABLE ... ADD CONSTRAINT fk_reservations_technician
    FOREIGN KEY (technician_id) REFERENCES opslab.users(id);
```
`technician_id` 컬럼과 인덱스는 유지(기사 식별엔 필요).

- [ ] **Step 2: 기존 운영 DB — 드롭 마이그레이션 추가**

운영처럼 이미 FK가 적용된 DB를 위해 멱등 드롭:
```sql
-- V3__drop_user_fk.sql : MSA DB 분리 — users 교차 FK 제거 (무결성은 기사 프로젝션이 담당)
ALTER TABLE reservation.reservations DROP CONSTRAINT IF EXISTS fk_reservations_technician;
```

- [ ] **Step 3: 검증 — FK 없이 마이그레이션·스키마 검증 통과**

Run: `./gradlew :reservation-service:test --tests "*ReservationMigration*"`
Expected: PASS, 그리고 `users` 테이블이 없는 DB에서도 성공(교차 의존 사라짐 증거).

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(reservation): drop cross-service FK to users (DB-per-service)"
```

---

## Chunk 4: Phase B — 기사 프로젝션(이벤트) + 라우팅 + 운영

### Task B4: user 멤버십/역할 이벤트 → reservation 로컬 기사 프로젝션

user 도메인이 승인/역할변경/정지 시 이벤트를 outbox 로 발행하고, reservation-service 가 consume 하여 `reservation.technician_view` 를 upsert 한다. `ReservationTechnicianPort` 의 Phase B 구현은 이 view 를 읽는다(임시 in-process 어댑터 폐기).

**Files (publisher 측 — order-service/user):**
- Create: `order-service/.../user/application/port/out/PublishUserEventPort.java`
- Create: `order-service/.../user/adapter/out/event/OutboxBackedUserEventPublisher.java` (템플릿: `payment/adapter/out/event/OutboxBackedEventPublisher.java`)
- Modify: user 멤버십 승인/역할변경 서비스 — 트랜잭션 안에서 publish 호출

**Files (consumer/projection 측 — reservation-service):**
- Create: `reservation-service/.../db/migration/V4__technician_view.sql`
- Create: `reservation-service/.../reservation/adapter/out/persistence/TechnicianViewJpaEntity.java` (+ repository)
- Create: `reservation-service/.../reservation/adapter/in/kafka/ReservationKafkaConfig.java` (★ 컨테이너 팩토리 + 에러핸들러 + 토픽 — 아래 주의 참조)
- Create: `reservation-service/.../reservation/adapter/in/kafka/UserMembershipEventConsumer.java` (템플릿: settlement `PaymentEventKafkaConsumer`)
- Create: `reservation-service/.../reservation/adapter/out/technician/ProjectionTechnicianAdapter.java` (implements `ReservationTechnicianPort`)
- Modify (producer 토픽): order-service 가 스캔하는 곳에 `lemuel.user.membership-changed` NewTopic 빈 추가(shared-common `KafkaConfig` 는 payment 토픽만 정의 → user 토픽 빈 신설 필요)
- Delete: `order-service/.../reservation_bridge/UserBackedTechnicianAdapter.java`

> **⚠ Kafka 인프라 결함 — 반드시 선결:** `kafkaListenerContainerFactory` 빈(재시도/DLT 에러핸들러 포함)은 shared-common 이 아니라 **settlement-service**(`KafkaErrorHandlerConfig`)에만 정의돼 있다. reservation-service 는 settlement 를 의존하지 않으므로 그 빈이 없다. shared-common `KafkaConfig` 는 `@EnableKafka` + payment 토픽 빈만 제공한다. 따라서 reservation-service 는 **자체 `ReservationKafkaConfig`** 가 필요하다: (a) settlement `KafkaErrorHandlerConfig` 를 복제·이식한 컨테이너 팩토리(또는 Spring Boot 기본 팩토리 사용 시 컨슈머의 `containerFactory=` 속성 제거), (b) `lemuel.user.membership-changed` 와 그 `.DLT` NewTopic 빈. 이 선결 없이 `@KafkaListener(containerFactory="kafkaListenerContainerFactory")` 를 쓰면 부팅 시 `NoSuchBeanDefinitionException` 으로 실패한다.

- [ ] **Step 1: 프로젝션 테이블 마이그레이션**

```sql
-- V4__technician_view.sql
CREATE TABLE reservation.technician_view (
    user_id           BIGINT PRIMARY KEY,
    role              VARCHAR(20)  NOT NULL,
    membership_status VARCHAR(20)  NOT NULL,
    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at        TIMESTAMP    NOT NULL DEFAULT now()
);
```

- [ ] **Step 2: user 측 발행 포트 + 어댑터 (outbox)**

`OutboxBackedEventPublisher` 패턴 그대로. `AGGREGATE_TYPE="User"`, eventType `UserMembershipChanged`, payload `{userId, role, membershipStatus, active}`. 발행 메서드 예:
```java
void publishMembershipChanged(Long userId, String role, String membershipStatus, boolean active);
```
user 의 `approveMembership/suspend/reinstate/changeRole` 가 호출되는 application service 의 `@Transactional` 안에서 publish.

- [ ] **Step 3: 실패 테스트 — 컨슈머가 view 를 upsert 한다**

reservation-service 통합테스트: `UserMembershipChanged` JSON 레코드를 컨슈머에 전달 → `technician_view` 에 row 존재 + 멱등(같은 event_id 재전송 시 중복 처리 안 함, `processed_events` 사용).

- [ ] **Step 3.5: ReservationKafkaConfig 선결 (인프라 결함 해소)**

`reservation-service/.../reservation/adapter/in/kafka/ReservationKafkaConfig.java` 작성. settlement `KafkaErrorHandlerConfig` 를 템플릿으로: `@ConditionalOnProperty(app.kafka.enabled)` 하에 (a) `kafkaListenerContainerFactory`(DefaultErrorHandler + ExponentialBackOff + DeadLetterPublishingRecoverer), (b) `lemuel.user.membership-changed` 및 `.DLT` NewTopic 빈. 또는 단순화하려면 Spring Boot 기본 팩토리에 맡기고 Step 4 컨슈머에서 `containerFactory=` 속성을 빼되, 이 경우 재시도/DLT 동작이 없음을 `log` 로 남긴다.
Run: `./gradlew :reservation-service:test --tests "*KafkaConfig*"` (또는 컨텍스트 로딩 테스트)로 빈 생성 확인.

- [ ] **Step 4: 컨슈머 구현 (settlement 템플릿 차용)**

`@KafkaListener(topics="${app.kafka.topic.user-membership-changed}", groupId="lemuel-reservation", containerFactory="kafkaListenerContainerFactory")`, `ProcessedEventRepository` 로 멱등(이미 shared-common `common.outbox.adapter.in.kafka` 에 존재 — reservation-service 가 `common` 스캔으로 사용 가능), payload 파싱 후 `technician_view` upsert. `@ConditionalOnProperty(app.kafka.enabled)`. Step 3.5 의 팩토리 빈이 선결되어야 부팅된다.

- [ ] **Step 5: Phase B 포트 어댑터로 교체**

```java
@Component
public class ProjectionTechnicianAdapter implements ReservationTechnicianPort {
    private final TechnicianViewRepository repo;
    public ProjectionTechnicianAdapter(TechnicianViewRepository repo) { this.repo = repo; }
    @Override public boolean isAssignableTechnician(Long technicianId) {
        return repo.findById(technicianId)
            .map(v -> "TECHNICIAN".equals(v.getRole())
                   && "APPROVED".equals(v.getMembershipStatus()) && v.isActive())
            .orElse(false);
    }
}
```
그리고 `order-service` 의 `UserBackedTechnicianAdapter` 와 `reservation_bridge` 패키지 삭제.

- [ ] **Step 6: 초기 백필**

Run-once 마이그레이션 또는 운영 스크립트로 기존 TECHNICIAN 들을 `technician_view` 에 시드(분리 직후 빈 view 로 인한 배정 실패 방지). 별도 DB라 SQL JOIN 불가 → user 측에서 전체 TECHNICIAN 에 대해 `UserMembershipChanged` 재발행하는 백필 엔드포인트/배치를 만든다.

- [ ] **Step 7: 테스트 통과 + 커밋**

Run: `./gradlew :reservation-service:test`
Expected: PASS

```bash
git add -A
git commit -m "feat(reservation): event-driven technician projection replaces in-process user lookup"
```

---

### Task B5: Gateway 라우팅 + 보안 와이어링

**Files:**
- Modify: gateway-service 라우팅 설정(현 위치 확인: `git grep -rn "reservations\|order-service\|uri:" -- gateway-service/`)
- Verify: reservation-service 가 shared-common `SecurityConfig`/`JwtAuthenticationFilter` 를 스캔(이미 `common` 스캔에 포함)

- [ ] **Step 1: /reservations/** 라우트를 reservation-service 로**

기존에 order-service(8088)로 가던 `/reservations/**` 라우트의 `uri` 를 `http://reservation-service:8083`(또는 lb://reservation-service)로 변경.

- [ ] **Step 2: 라우팅·인증 e2e 확인**

게이트웨이(8080) 경유로 토큰을 들고 예약 등록/조회/기사배정 호출 → reservation-service 처리 확인. 토큰 없는 요청 401.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat(gateway): route /reservations/** to reservation-service"
```

---

### Task B6: 컨테이너/오케스트레이션/CI

**Files:**
- Modify: `Dockerfile`(MODULE 빌드 인자 — reservation-service 추가 동작 확인), `docker-compose.yml`(reservation-service + reservations_db), `k8s/`(매니페스트/헬름), CI 파이프라인(`MODULE=reservation-service`)

- [ ] **Step 1: 이미지 빌드 인자 확인**

Run: `docker build --build-arg MODULE=reservation-service -t lemuel-reservation .`
Expected: bootJar 가 생성되어 이미지 빌드 성공(Task B1로 bootJar 활성화됨).

- [ ] **Step 2: compose 에 서비스 + DB 추가**

`reservations_db`(Postgres) 서비스와 `reservation-service`(8083, DB/JWT/Kafka 환경변수) 추가. order-service env 에서 reservation 관련 제거.

- [ ] **Step 3: k8s/헬름 매니페스트 추가** (settlement/order 차트 복제·수정)

- [ ] **Step 4: 로컬 스택 기동 검증**

Run: `docker compose up -d` 후 `/actuator/health` (8083) 200 확인, 게이트웨이 경유 예약 happy-path 통과.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "ops(reservation): docker-compose, k8s, CI for standalone reservation-service"
```

---

### Task B7: Phase B 완료 검증

- [ ] **Step 1: 전체 빌드**

Run: `./gradlew build`
Expected: 모든 모듈 그린.

- [ ] **Step 2: 코드 경계 재확인(정적)**

Run: `git grep -n "import github.lms.lemuel.reservation" -- order-service/src settlement-service/src`
Expected: **0줄** (어떤 서비스도 reservation 코드를 참조하지 않음).
Run: `git grep -n "import github.lms.lemuel.\(user\|order\|product\)" -- reservation-service/src`
Expected: **0줄**.

- [ ] **Step 3: DB 분리 확인**

reservations_db 에 `users`/`orders` 테이블이 없고, `reservations`/`technician_view` 만 존재. 교차 FK 0.

- [ ] **Step 4: 독립 배포·장애격리 스모크**

order-service 를 내린 상태에서 reservation-service 단독으로 예약 등록/조회/기사배정(프로젝션 기준)이 동작.
Expected: order-service 다운과 무관하게 정상 → 진짜 독립.

- [ ] **Step 5: 문서 갱신 + Commit**

`CLAUDE.md` 모듈 구조/서비스 책임/이벤트 흐름에 reservation-service(8083, reservations_db, user 멤버십 이벤트 consume) 반영.
```bash
git add -A
git commit -m "docs(reservation): document reservation-service as standalone MSA; Phase B complete"
```

---

## 리스크 & 주의

- **A3 principal 변경의 폭발 반경:** `authentication.getName()`(email 기대) 사용처가 order-service 전반에 있을 수 있다. A3 Step 6에서 전수 grep + 전체 테스트 필수. 가장 회귀 위험이 큰 단계.
- **구 토큰 호환:** `uid` claim 없는 기존 토큰은 `p.userId()==null`. reservation 컨트롤러는 이 경우 401 처리(재로그인 유도). 무중단이 필요하면 발급부 배포 → TTL 경과 → 컨트롤러 강제화 순서로.
- **백필 누락 시 배정 실패:** Phase B 컷오버 직후 `technician_view` 가 비면 모든 기사배정이 400. B4 Step 6 백필을 컷오버 전에 수행.
- **이벤트 유실 시 결과적 일관성:** 멤버십 변경이 view 에 늦게 반영될 수 있음. 배정은 "최근 승인 직후"엔 실패할 수 있으므로, 재시도 안내 또는 동기 조회 폴백(선택)을 운영 정책으로 결정.
- **마이그레이션 분리 충돌:** reservation 마이그레이션을 order-service classpath 에서 제거하면, order-service Flyway 히스토리에는 이미 적용 기록이 남아 있다. 운영 DB 분리 시 `flyway baseline`/스키마 분리 전략을 별도 검토(이 계획은 신규/Testcontainers 기준). 또한 settlement-service 는 자체 migration 디렉터리가 없어 정산 마이그레이션도 order-service 에 집중돼 있다 — reservation 분리는 이 중앙집중 구조를 처음으로 깨는 사례이므로, "어느 서비스가 어느 V 파일을 소유하는가"의 규칙을 분리 시 함께 정한다.
- **A2 의 비컴파일 중간 커밋:** Task A2→A3→A4 는 하나의 논리 단위이며 A2 커밋은 의도적으로 빌드가 깨진 상태다. "모든 커밋이 빌드된다" 원칙과 충돌하므로, (1) 반드시 feature 브랜치에서 진행하고 main/CI 게이트는 A4 이후의 그린 상태에만 적용하거나, (2) 머지 전 A2~A4 를 squash 한다. 단독 PR 로 A2 만 올리지 말 것.
- **shared-common SecurityConfig 재사용:** reservation-service 는 별도 SecurityConfig 가 불필요하다 — `common` 스캔으로 shared-common 의 `SecurityConfig`+`JwtAuthenticationFilter` 가 적용되고, `/reservations/**` 는 명시 matcher 가 없어 `anyRequest().authenticated()` 로 떨어진다(역할 체크는 컨트롤러가 수행). 단 그 SecurityConfig 에는 /orders·/settlements 등 타 서비스용 matcher 가 함께 들어 있다(무해하지만 인지 필요). 분리 성숙 시 서비스별 SecurityConfig 분할을 후속 검토.

---

## 실행 핸드오프

Phase 경계(A 완료 = Chunk 2 끝, B 완료 = Chunk 4 끝)마다 빌드 그린을 확인하고 중간 점검을 받는다. subagent 가 가능하면 **superpowers:subagent-driven-development** 로 Task 단위 실행 + 2단계 리뷰를 진행한다.
