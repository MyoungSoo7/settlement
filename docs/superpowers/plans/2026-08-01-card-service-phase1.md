# card-service 1단계 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 셀러 정산 데이터를 재원으로 법인 카드 한도를 심사하고, 그 한도 안에서 임직원 카드를 발급하는 `card-service` 를 신설한다.

**Architecture:** 헥사고날(ports & adapters) 독립 부팅 서비스. 자체 DB `lemuel_card`(스키마명은 `opslab` 재사용). 재원은 account-service 내부 REST 로 동기 조회하고, 조직·멤버·평판은 Kafka 이벤트 프로젝션으로 유지한다. 발행은 전부 Outbox 경유.

**Tech Stack:** Java 25 / Spring Boot 4.0.4 / PostgreSQL 17 / Flyway / Kafka(Redpanda) / Testcontainers / ArchUnit / JaCoCo

**Spec:** `docs/superpowers/specs/2026-08-01-card-service-phase1-design.md`

## Global Constraints

- **금액은 반드시 `BigDecimal`.** `double`/`float` 로 금액을 선언·연산·파싱 금지. 생성은 문자열로(`new BigDecimal("0.70")`), 비교는 `compareTo`, `divide`/`setScale` 에 `RoundingMode.HALF_UP` 명시. (`money-safety`)
- **shared-common `Money` 에는 `divide`·`isLessThan`·`isGreaterThanOrEqualTo` 가 없다.** 있는 것만 쓴다: `of`, `won`, `plus`, `minus`, `times`, `negate`, `min`, `max`, `isNegative`, `isPositive`, `isZero`, `isZeroOrNegative`, `isGreaterThan`, `isLessThanOrEqualTo`, `toBigDecimal`. `Money` 는 scale 2 HALF_UP 로 정규화된다.
- **TDD.** 실패하는 테스트를 먼저 작성하고 **실패를 직접 목격**한 뒤 최소 구현한다. 돈 경로는 예외 없음. (`tdd-discipline`)
- **커버리지 게이트:** 루트 `build.gradle.kts` 의 JaCoCo **LINE 90%** 가 subprojects 로 상속된다. 신규 모듈 예외 없음. 커버리지 제외 설정을 임의로 확장하지 않는다.
- **이벤트 발행은 Outbox 경유만.** 비즈니스 트랜잭션 안에서 `kafkaTemplate.send()` 직접 호출 금지. (`idempotency-and-events`)
- **컨슈머 첫 줄은 멱등 체크.** `IdempotentEventConsumer` 를 상속하면 골격이 처리한다. 멱등 체크와 비즈니스 로직은 같은 트랜잭션.
- **메인 클래스 패키지는 루트 `github.lms.lemuel`.** 그래야 shared-common 의 `github.lms.lemuel.common.*` 빈(SecurityConfig·Outbox 어댑터·ProcessedEventRepository·Audit)이 스캔된다.
- **`spring.jpa.properties.hibernate.default_schema: opslab` 고정.** shared-common Outbox claim 네이티브 쿼리가 `opslab.outbox_events` 를 하드코딩한다. 스키마명을 바꾸면 폴러가 조용히 깨진다.
- **이벤트 토픽 명명:** `lemuel.{도메인}.{이벤트}`. 신규 토픽은 스키마 + 정본 샘플 + 프로듀서 계약테스트 + 컨슈머 계약테스트 4자가 항상 같이 움직인다. (`event-contract-change`)
- **`ErrorCode` 와 `AuditAction` 은 shared-common enum.** card 전용 상수는 shared-common 에 추가해야 컴파일된다 (Task 1).
- **서비스별 예외 핸들러는 `@Order(Ordered.HIGHEST_PRECEDENCE)`** — shared-common `GlobalExceptionHandler`(LOWEST)보다 먼저 잡아야 404/403/409/422 매핑이 유지된다.

---

## File Structure

### 신규 모듈 `card-service`

| 파일                                                            | 책임                                                      |
| --------------------------------------------------------------- | --------------------------------------------------------- |
| `src/main/java/github/lms/lemuel/CardServiceApplication.java`   | 부팅 진입점 (루트 패키지)                                 |
| `card/domain/CardAccount.java`                                  | 법인 카드계정 애그리거트 — 마스터 한도, 상태, 심사 스냅샷 |
| `card/domain/Card.java`                                         | 임직원 카드 애그리거트 — 서브한도, 소지자, 상태           |
| `card/domain/CardAccountStatus.java` · `CardStatus.java`        | 상태머신 enum (전이 규칙 포함)                            |
| `card/domain/ReputationGrade.java`                              | 평판 등급 enum + haircut 계수                             |
| `card/domain/OrgRole.java`                                      | 조직 역할 enum (card 자체 — organization 의존 금지)       |
| `card/domain/LimitChangeResult.java`                            | 한도 변경 결과 (적용값 + 클램프 여부)                     |
| `card/domain/CardLimitPolicy.java`                              | 한도 산정 순수 정책                                       |
| `card/domain/LimitSnapshot.java`                                | 산정 근거 스냅샷 값 객체                                  |
| `card/domain/exception/*.java`                                  | 도메인 불변식 위반 예외                                   |
| `card/application/port/in/*.java`                               | 유스케이스 인터페이스 + 커맨드 레코드                     |
| `card/application/port/out/*.java`                              | 영속·이벤트·재원조회·프로젝션 포트                        |
| `card/application/service/*.java`                               | 유스케이스 구현                                           |
| `card/application/service/CardOrgAuthorizer.java`               | 조직 역할 기반 인가 (IDOR 방지)                           |
| `card/adapter/in/web/CardController.java` + `dto/`              | REST 표면                                                 |
| `card/adapter/in/web/CardExceptionHandler.java`                 | 도메인 예외 → HTTP 매핑                                   |
| `card/adapter/in/kafka/*Consumer.java`                          | organization 3종 + company 1종 소비                       |
| `card/adapter/in/schedule/CardLimitRecalculationScheduler.java` | 일 1회 재산정                                             |
| `card/adapter/out/persistence/*.java`                           | JPA 엔티티·리포지토리·어댑터                              |
| `card/adapter/out/event/CardEventPublisherAdapter.java`         | Outbox 발행                                               |
| `card/adapter/out/external/AccountFundingAdapter.java`          | account-service 재원 조회                                 |
| `card/adapter/out/external/MockCardIssuerAdapter.java`          | 카드 채번 mock                                            |
| `src/main/resources/db/migration/V4__card_core.sql` 외 3종      | Flyway                                                    |

### 기존 파일 수정

| 파일                                                                                 | 변경                             |
| ------------------------------------------------------------------------------------ | -------------------------------- |
| `settings.gradle.kts`                                                                | `include("card-service")`        |
| `Dockerfile`                                                                         | COPY 2곳                         |
| `docker-compose.yml`                                                                 | `card-postgres` + `card-service` |
| `gateway-service/src/main/resources/application.yml`                                 | `/api/cards/**` 라우트           |
| `nginx.conf`, `frontend/nginx.conf`                                                  | 프론트 경유 경로                 |
| `.github/workflows/ci.yml`                                                           | paths-filter + image 매핑 2곳    |
| `shared-common/.../exception/ErrorCode.java`                                         | `CARD_*` 상수                    |
| `shared-common/.../audit/domain/AuditAction.java`                                    | `CARD_*` 상수                    |
| `shared-common/.../config/jwt/SecurityConfig.java`                                   | `/api/cards/**` 인가 규칙        |
| `shared-common/src/testFixtures/resources/contracts/events/`                         | 스키마 8종 + 샘플 8종            |
| `account-service/.../adapter/in/web/InternalAccountController.java`                  | 신규 내부 API                    |
| `account-service/.../application/port/out/LoadAccountEntryPort.java`                 | `balanceOf` 추가                 |
| `account-service/src/main/resources/application.yml` (+ `application-prod.yml` 신규) | `app.internal.api-key`           |
| `organization-service/.../adapter/out/event/OrganizationEventPublisherAdapter.java`  | 이벤트 2종 발행                  |
| `SPEC.md`                                                                            | §5 토픽 표 + 서비스 절           |
| `HARNESS.md` + `.claude/skills/card-service-rules/SKILL.md`                          | 하네스 배선                      |

---

## Task 순서 개요

| #   | 태스크                        | 산출물                                    |
| --- | ----------------------------- | ----------------------------------------- |
| 0   | 부팅 가능한 빈 모듈           | `/actuator/health` 200                    |
| 1   | shared-common 확장            | `CARD_*` ErrorCode·AuditAction, 인가 규칙 |
| 2   | account-service 재원 내부 API | `/internal/account/sellers/{id}/funding`  |
| 3   | organization 이벤트 2종 신설  | `member_role_changed`·`member_removed`    |
| 4   | 카드 도메인 애그리거트        | `CardAccount`·`Card` + 상태머신           |
| 5   | 한도 정책                     | `CardLimitPolicy`                         |
| 6   | 영속 계층                     | Flyway + JPA 어댑터                       |
| 7   | 이벤트 소비 프로젝션          | 조직·멤버·평판                            |
| 8   | 재원 조회 어댑터              | RestClient + 회로차단 + 503               |
| 9   | 카드계정 개설(심사)           | `POST /api/cards/accounts`                |
| 10  | 임직원 카드 발급              | 비관적 락 + 동시성 IT                     |
| 11  | 한도·상태 변경                | `PATCH` 2종                               |
| 12  | 이탈자 카드 자동 정지         | `member_removed` 연결                     |
| 13  | 일 1회 재산정                 | 스케줄러 + 클램프                         |
| 14  | 2단계 계약 선확정             | `card.authorized`·`card.captured` 스키마  |
| 15  | 하네스·문서 배선              | SPEC.md·스킬·harness-audit                |

### 스펙 대비 변경 1건 (조사 결과 반영)

스펙 §8 은 재원 조회 실패에 "Resilience4j 회로차단과 재시도"를 적었으나, **리포에서 `/internal/**`호출에 Resilience4j 를 쓰는 전례는 없다.** resilience4j 의존과 설정 블록은 order-service 의 PG 어댑터(Toss/KCP/NICE/Inicis) 전용이고, settlement·loan·account 에는 의존성조차 없다. 대신 settlement 의`OrderReconClient` 가 **수제 재시도 루프**(총 2회, 200ms 백오프, 4xx 즉시 실패 / 5xx·연결실패 재시도, 최종 전용 예외로 번역)를 표준으로 삼고 있다.

Task 8 은 이 전례를 따른다. Resilience4j 도입은 리포 최초 도입이라 별도 판단이 필요하며 1단계 범위 밖이다. 실패 시 `503` 으로 번역한다는 스펙의 본질(폴백 없음)은 그대로 유지된다.

---

## Task 0: 부팅 가능한 빈 모듈

**Files:**

- Create: `card-service/build.gradle.kts`
- Create: `card-service/src/main/java/github/lms/lemuel/CardServiceApplication.java`
- Create: `card-service/src/main/resources/application.yml`
- Create: `card-service/src/main/resources/db/migration/V2__outbox_processed_events.sql`
- Create: `card-service/src/main/resources/db/migration/V3__audit_logs.sql`
- Create: `card-service/src/test/java/github/lms/lemuel/card/CardArchitectureTest.java`
- Create: `card-service/src/test/java/github/lms/lemuel/card/integration/CardBootIT.java`
- **코어 스키마 마이그레이션은 이 태스크에서 만들지 않는다** — Task 6 이 `V4__card_core.sql` 로 작성한다.
  Flyway 는 V2 부터 시작해도 **빈 DB 에서는** 정상 동작한다(버전 번호는 순서만 정한다) — 하지만 이 리포는
  `docker-compose.yml` 에 영속 볼륨(`card-postgres-data`)을 두므로, 이 브랜치로 한 번이라도 `docker compose up`
  한 개발자의 DB 에는 V2·V3 만 기록된 채 남는다. 그 뒤 코어 스키마를 V1 로 끼워 넣으면 `validateOnMigrate=true`/
  `outOfOrder=false`(둘 다 기본값)에서 "Detected resolved migration not applied to database: 1" 로 부팅이 깨진다.
  그래서 Task 6 은 V1 이 아니라 **후행 버전 V4** 를 쓴다 — 기존에 부팅된 DB 에도 안전하게 추가된다.
- Modify: `settings.gradle.kts:8-23`
- Modify: `Dockerfile` (COPY 2곳)
- Modify: `docker-compose.yml`
- Modify: `gateway-service/src/main/resources/application.yml`
- Modify: `nginx.conf`, `frontend/nginx.conf`
- Modify: `.github/workflows/ci.yml`

**Interfaces:**

- Consumes: 없음 (최초 태스크)
- Produces: 부팅되는 `card-service` 모듈. 이후 모든 태스크가 이 안에 코드를 추가한다. 포트 8106(app) / 8107(management), DB `lemuel_card`, 스키마 `opslab`.

- [ ] **Step 1: 모듈 등록 — `settings.gradle.kts`**

`include(...)` 목록의 `"organization-service",` 다음 줄에 추가:

```kotlin
    "organization-service",
    "card-service",
)
```

- [ ] **Step 2: `card-service/build.gradle.kts` 작성**

organization-service 와 동일하되 주석만 교체한다. Kafka 는 발행(Outbox) + 소비(organization·company) 양쪽에 쓴다.

```kotlin
plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

// ★ card-service 는 자체 DB(lemuel_card) 를 소유하는 DB-per-service 독립 부팅 서비스.
//   셀러 법인의 카드계정(마스터 한도)과 임직원 카드(서브한도)를 관리한다.
//   재원(확정·미지급 정산금 + 홀드백)은 account-service 내부 REST 로 조회하고,
//   조직·멤버·평판은 Kafka 이벤트 프로젝션으로 유지한다 — 타 서비스 코드·DB 의존 0.

dependencies {
    implementation("github.lms.lemuel:shared-common:1.0.0")
    testImplementation(testFixtures("github.lms.lemuel:shared-common:1.0.0"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("org.springframework.boot:spring-boot-starter-cache")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-flyway")

    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.kafka:spring-kafka")

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2")
    implementation("com.github.ben-manes.caffeine:caffeine")
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
    testImplementation("org.springframework.boot:spring-boot-starter-jackson-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.kafka:spring-kafka-test")
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

- [ ] **Step 3: 진입점 작성**

`card-service/src/main/java/github/lms/lemuel/CardServiceApplication.java` — **패키지가 루트 `github.lms.lemuel` 이어야 한다.** 그래야 shared-common 의 SecurityConfig·Outbox·Audit 빈이 스캔된다.

```java
package github.lms.lemuel;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * card-service 독립 부팅 진입점.
 *
 * <p>★ 자체 DB(lemuel_card) 를 소유하는 DB-per-service 이므로 독립 {@code @SpringBootApplication} 을 가진다
 * (organization/investment/loan 패턴 미러링).
 *
 * <p>루트 {@code github.lms.lemuel} 에서 스캔 → card 패키지 + shared-common(JWT SecurityConfig·Outbox·
 * 멱등 인프라·Audit) 빈만 잡힌다. 타 서비스 패키지는 build.gradle.kts 의존에 없어 클래스패스에 없다.
 *
 * <p>{@code @EnableScheduling} 은 일 1회 한도 재산정(Task 13)에 필요하다.
 */
@SpringBootApplication
@EnableCaching
@EnableScheduling
public class CardServiceApplication {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
        SpringApplication.run(CardServiceApplication.class, args);
    }
}
```

- [ ] **Step 4: `application.yml` 작성**

organization-service 의 것을 복제하고 이름·포트·DB·토픽만 교체한다. `default_schema: opslab` 은 **바꾸지 않는다**.

```yaml
spring:
  profiles:
    active: local

  application:
    name: lemuel-card

  threads:
    virtual:
      enabled: true

  # ★ card-service 는 자체 DB(lemuel_card) 를 소유한다 (DB-per-service).
  #   재원은 account-service 내부 REST 로 조회하고, 조직·평판은 Kafka 이벤트로만 받는다(코드·DB 의존 0).
  datasource:
    url: jdbc:postgresql://localhost:5447/lemuel_card?reWriteBatchedInserts=true
    username: ${POSTGRES_USER}
    password: ${POSTGRES_PASSWORD}
    driver-class-name: org.postgresql.Driver
    hikari:
      pool-name: lemuel-card-pool
      maximum-pool-size: ${DB_POOL_MAX:20}
      minimum-idle: ${DB_POOL_MIN_IDLE:5}
      connection-timeout: 10000
      leak-detection-threshold: 30000
      max-lifetime: 1800000 # 30분 — 유휴 커넥션 CNI/conntrack 만료로 조용히 죽는 것 방지
      keepalive-time: 300000 # 5분

  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    open-in-view: false
    properties:
      hibernate:
        # ★ 스키마명은 opslab 재사용(물리 DB 는 lemuel_card 로 분리).
        #   shared-common Outbox claim 네이티브 쿼리가 'opslab.outbox_events' 를 하드코딩한다.
        default_schema: opslab
        jdbc:
          batch_size: 50
          batch_versioned_data: true
        order_inserts: true
        order_updates: true

  flyway:
    enabled: true
    schemas: opslab
    locations: classpath:db/migration

  cache:
    type: caffeine
    caffeine:
      spec: maximumSize=500,expireAfterWrite=600s

  kafka:
    bootstrap-servers: ${SPRING_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    consumer:
      group-id: lemuel-card
      auto-offset-reset: earliest
      enable-auto-commit: false
      properties:
        isolation.level: read_committed
    listener:
      ack-mode: manual_immediate
      missing-topics-fatal: false

  lifecycle:
    timeout-per-shutdown-phase: 30s

server:
  port: 8106
  shutdown: graceful
  servlet:
    encoding:
      charset: UTF-8
      enabled: true
      force: true

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
      base-path: /actuator
  endpoint:
    prometheus:
      enabled: true
    health:
      show-details: when-authorized
      probes:
        enabled: true
  health:
    elasticsearch:
      enabled: false
    db:
      enabled: true
  server:
    port: 8107
  metrics:
    tags:
      application: ${spring.application.name}
      environment: ${ENVIRONMENT:local}

springdoc:
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui.html

app:
  # JWT — shared-common JwtProperties(prefix=app.jwt). order-service 와 동일 시크릿.
  jwt:
    issuer: ${JWT_ISSUER:lemuel}
    secret: ${JWT_SECRET}
    ttl-seconds: ${JWT_TTL_SECONDS:3600}

  # account-service 내부 재원 조회 (Task 8)
  account-service:
    base-url: ${ACCOUNT_SERVICE_URL:http://localhost:8102}
  internal:
    api-key: ${INTERNAL_API_KEY:}

  # 한도 정책 파라미터 (Task 5)
  card:
    limit:
      recognition-ratio: ${CARD_LIMIT_RECOGNITION_RATIO:0.70}
      minimum: ${CARD_LIMIT_MINIMUM:300000}
      recalculation-cron: ${CARD_LIMIT_RECALC_CRON:0 30 3 * * *}

  kafka:
    enabled: ${APP_KAFKA_ENABLED:false}
    consumer:
      concurrency: ${APP_KAFKA_CONSUMER_CONCURRENCY:3}
    topic:
      partitions: ${APP_KAFKA_TOPIC_PARTITIONS:3}
      # 발행 (card → account)
      card-account-opened: lemuel.card.account_opened
      card-issued: lemuel.card.issued
      card-limit-changed: lemuel.card.limit_changed
      card-status-changed: lemuel.card.status_changed
      # 수신 (organization → card)
      organization-created: lemuel.organization.created
      organization-member-joined: lemuel.organization.member_joined
      organization-member-role-changed: lemuel.organization.member_role_changed
      organization-member-removed: lemuel.organization.member_removed
      # 수신 (company → card)
      company-reputation-changed: lemuel.company.reputation_changed
```

`card-service/src/main/resources/application-prod.yml` 도 함께 만든다:

```yaml
# 운영(prod) 프로파일. card-service 는 /internal/** 를 호스팅하지 않지만,
# account-service 의 내부 API 를 호출하므로 INTERNAL_API_KEY 주입이 필수다.
# 키가 없으면 헤더가 생략되어 account 가 fail-closed 로 401 을 반환한다(Task 2).
app:
  security:
    internal-key-required: true
```

- [ ] **Step 5: Flyway V2 / V3 작성**

`V2__outbox_processed_events.sql` 과 `V3__audit_logs.sql` 은 organization-service 의 것을 복제하되 **인덱스·제약 이름 접두를 `organization` → `card` 로 전부 치환**한다. V3 는 organization 이 나중에 파티션으로 전환한 것과 달리 **처음부터 파티션드 부모 + append-only 트리거 + 함수 2종**으로 작성한다(리네임/이관 단계 생략).

추가로 `V2` 에 다음을 포함한다 (organization 의 V20260717100100 + V20260728010000 을 병합):

- `prune_outbox_published(INTERVAL DEFAULT '7 days')` / `prune_processed_events(INTERVAL DEFAULT '30 days')` — 최소 7일 가드 포함
- outbox 봉투 컬럼 `occurred_at TIMESTAMPTZ NOT NULL` / `event_version INTEGER NOT NULL DEFAULT 1` / `producer VARCHAR(64)` (처음부터 포함하므로 백필 UPDATE 불필요)
- `shedlock` 테이블 (Task 13 스케줄러가 요구):

```sql
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
```

- [ ] **Step 6: 배선 5곳**

1. **`Dockerfile`** — build.gradle.kts COPY 블록(`:14-27` 부근)과 소스 COPY 블록(`:31-45` 부근) **양쪽에** `card-service` 추가. 누락 시 settings 평가 실패로 **전체 이미지 빌드가 깨진다.**
2. **`gateway-service/src/main/resources/application.yml`** — organization 라우트(`:71` 부근) 다음에:

```yaml
- id: card-service
  uri: ${CARD_SERVICE_URI:http://localhost:8106}
  predicates:
    - Path=/api/cards/**
```

3. **`docker-compose.yml`** — `card-postgres`(포트 5447 매핑) + `card-service`(`MODULE=card-service`, `image: ghcr.io/myoungsoo7/lemuel-card:latest`, `127.0.0.1:8106:8080`). 환경변수에 `INTERNAL_API_KEY: ${INTERNAL_API_KEY:-lemuel-internal-dev-key}` 를 **반드시 포함**(Task 2 에서 account 가 fail-closed 로 바뀐다).
4. **`nginx.conf` / `frontend/nginx.conf`** — `/api/cards/` 프록시 경로.
5. **`.github/workflows/ci.yml`** — paths-filter 목록과 module→image_suffix 매핑 **2곳 모두**.

- [ ] **Step 7: ArchUnit 테스트 작성 (실패 확인용 최초 테스트)**

`card-service/src/test/java/github/lms/lemuel/card/CardArchitectureTest.java`:

```java
package github.lms.lemuel.card;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * card-service 의 헥사고날 경계 + MSA 코드 경계 가드 (organization/investment 패턴).
 */
class CardArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("github.lms.lemuel.card");
    }

    @Test
    void 도메인은_application_adapter_config_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..card.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..card.application..", "..card.adapter..", "..card.config..")
                .allowEmptyShould(true);
        rule.check(classes);
    }

    @Test
    void application_은_adapter_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..card.application..")
                .should().dependOnClassesThat()
                .resideInAPackage("..card.adapter..")
                .allowEmptyShould(true);
        rule.check(classes);
    }

    @Test
    void card_는_타_서비스_도메인에_코드의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("github.lms.lemuel.card..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "github.lms.lemuel.order..",
                        "github.lms.lemuel.settlement..",
                        "github.lms.lemuel.loan..",
                        "github.lms.lemuel.investment..",
                        "github.lms.lemuel.account..",
                        "github.lms.lemuel.organization..")
                .allowEmptyShould(true);
        rule.check(classes);
    }

    @Test
    void 도메인은_JPA_와_Spring_에_의존하지_않는다() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..card.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("jakarta.persistence..", "org.springframework..")
                .allowEmptyShould(true);
        rule.check(classes);
    }
}
```

- [ ] **Step 8: 부팅 IT 작성 — 실패를 먼저 목격**

`card-service/src/test/java/github/lms/lemuel/card/integration/CardBootIT.java`:

```java
package github.lms.lemuel.card.integration;

import github.lms.lemuel.CardServiceApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 부팅 스모크 — 실 PostgreSQL(Testcontainers)에서 Flyway 가 opslab 스키마를 만들고
 * 컨텍스트가 기동되는지 확인한다. Outbox 폴러가 opslab.outbox_events 를 하드코딩하므로
 * 스키마명이 어긋나면 여기서 먼저 깨져야 한다.
 */
@SpringBootTest(
        classes = CardServiceApplication.class,
        properties = {
                "app.kafka.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class CardBootIT {

    static boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("card_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("POSTGRES_USER", POSTGRES::getUsername);
        r.add("POSTGRES_PASSWORD", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("Flyway 가 opslab 스키마에 outbox·processed_events·shedlock 을 만든다")
    void flywayCreatesInfrastructureTables() {
        assertThat(tableExists("outbox_events")).isTrue();
        assertThat(tableExists("processed_events")).isTrue();
        assertThat(tableExists("shedlock")).isTrue();
        assertThat(tableExists("audit_logs")).isTrue();
    }

    private boolean tableExists(String table) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'opslab' AND table_name = ?
                """, Integer.class, table);
        return n != null && n > 0;
    }
}
```

- [ ] **Step 9: 테스트 실행 — 실패 확인**

Run: `./gradlew :card-service:test`
Expected: 컴파일은 통과하고 `CardBootIT` 가 **Flyway 미작성 테이블로 실패**하거나, V2/V3 미작성 시 컨텍스트 로딩 실패. ArchUnit 은 클래스가 없어 `allowEmptyShould(true)` 로 통과.

- [ ] **Step 10: V2/V3 를 완성해 통과시키기**

Run: `./gradlew :card-service:test`
Expected: PASS

- [ ] **Step 11: 3층 경로 확인**

```bash
docker compose up -d card-postgres card-service
curl -s localhost:8106/actuator/health     # 직접 포트
curl -s localhost:8080/api/cards/health    # gateway (404 정상 — 아직 컨트롤러 없음, 502/타임아웃이면 라우트 문제)
```

- [ ] **Step 12: 커밋**

```bash
git add settings.gradle.kts Dockerfile docker-compose.yml nginx.conf frontend/nginx.conf \
        .github/workflows/ci.yml gateway-service/src/main/resources/application.yml card-service
git commit -m "feat(card): card-service 모듈 스캐폴딩 — 부팅·Flyway·배선"
```

---

## Task 1: shared-common 확장 (ErrorCode · AuditAction · 인가 규칙)

**Files:**

- Modify: `shared-common/src/main/java/github/lms/lemuel/common/exception/ErrorCode.java`
- Modify: `shared-common/src/main/java/github/lms/lemuel/common/audit/domain/AuditAction.java`
- Modify: `shared-common/src/main/java/github/lms/lemuel/common/config/jwt/SecurityConfig.java`
- Test: `shared-common/src/test/java/github/lms/lemuel/common/exception/ErrorCodeTest.java` (기존 파일에 추가, 없으면 생성)

**Interfaces:**

- Consumes: 없음
- Produces:
  - `ErrorCode.CARD_ACCOUNT_NOT_FOUND` (404) · `CARD_NOT_FOUND` (404) · `CARD_ACCOUNT_ALREADY_EXISTS` (409) · `CARD_ALREADY_ISSUED` (409) · `CARD_SCREENING_REJECTED` (422) · `CARD_SUB_LIMIT_EXCEEDED` (422) · `CARD_HOLDER_NOT_MEMBER` (422) · `CARD_FUNDING_UNAVAILABLE` (503) · `CARD_FORBIDDEN` (403)
  - `AuditAction.CARD_ACCOUNT_OPENED` · `CARD_ISSUED` · `CARD_LIMIT_CHANGED` · `CARD_STATUS_CHANGED`
  - `/api/cards/**` 는 `authenticated()` (역할 판정은 서비스 내 `CardOrgAuthorizer` 가 조직 멤버십으로 수행)

- [ ] **Step 1: 실패 테스트 작성**

`shared-common/src/test/java/github/lms/lemuel/common/exception/CardErrorCodeTest.java`:

```java
package github.lms.lemuel.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * card-service 가 사용하는 ErrorCode 상수의 HTTP 상태 매핑 고정.
 * 스펙 §8 의 표가 정본 — 여기서 어긋나면 컨트롤러 계약이 조용히 바뀐다.
 */
class CardErrorCodeTest {

    @Test
    @DisplayName("재원 조회 실패는 503 — 폴백 없이 명시적 실패")
    void fundingUnavailableIs503() {
        assertThat(ErrorCode.CARD_FUNDING_UNAVAILABLE.status())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("심사 탈락·한도 초과·비멤버 발급은 422")
    void businessRejectionsAre422() {
        assertThat(ErrorCode.CARD_SCREENING_REJECTED.status())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ErrorCode.CARD_SUB_LIMIT_EXCEEDED.status())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ErrorCode.CARD_HOLDER_NOT_MEMBER.status())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("중복은 409, 미존재는 404, 권한 부족은 403")
    void conflictNotFoundForbidden() {
        assertThat(ErrorCode.CARD_ACCOUNT_ALREADY_EXISTS.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.CARD_ALREADY_ISSUED.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.CARD_ACCOUNT_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.CARD_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.CARD_FORBIDDEN.status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("code() 는 enum 이름 그대로 — 응답 본문 errorCode 계약")
    void codeIsEnumName() {
        assertThat(ErrorCode.CARD_SCREENING_REJECTED.code()).isEqualTo("CARD_SCREENING_REJECTED");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :shared-common:test --tests '*CardErrorCodeTest'`
Expected: **컴파일 실패** — `cannot find symbol: CARD_FUNDING_UNAVAILABLE`. (이 경우는 컴파일 실패가 정상 RED 다 — 상수 자체가 없으므로.)

- [ ] **Step 3: `ErrorCode` 에 상수 추가**

`ErrorCode.java` 의 마지막 상수(`ENTRY_AMOUNT_SCALE_EXCEEDED`) 앞에 card 섹션을 추가한다. **마지막 상수 뒤에는 세미콜론이 있으므로 순서에 주의.**

```java
    // ─── card (법인카드) ───
    CARD_ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "카드계정을 찾을 수 없습니다."),
    CARD_NOT_FOUND(HttpStatus.NOT_FOUND, "카드를 찾을 수 없습니다."),
    CARD_ACCOUNT_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 카드계정이 존재하는 조직입니다."),
    CARD_ALREADY_ISSUED(HttpStatus.CONFLICT, "이미 활성 카드를 보유한 임직원입니다."),
    CARD_SCREENING_REJECTED(HttpStatus.UNPROCESSABLE_ENTITY, "카드 심사 기준을 충족하지 못했습니다."),
    CARD_SUB_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "임직원 한도 합계가 법인 마스터 한도를 초과합니다."),
    CARD_HOLDER_NOT_MEMBER(HttpStatus.UNPROCESSABLE_ENTITY, "해당 조직의 활성 구성원이 아닙니다."),
    CARD_FORBIDDEN(HttpStatus.FORBIDDEN, "이 작업을 수행할 권한이 없습니다."),
    // 재원 조회 실패는 폴백 없이 명시적 실패시킨다 — 재원을 모른 채 추정 한도를 주면 그 자체가 여신 사고다.
    CARD_FUNDING_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "재원 조회에 실패했습니다. 잠시 후 다시 시도해주세요."),
```

- [ ] **Step 4: `AuditAction` 에 상수 추가**

```java
    CARD_ACCOUNT_OPENED,
    CARD_ISSUED,
    CARD_LIMIT_CHANGED,
    CARD_STATUS_CHANGED,
```

- [ ] **Step 5: `SecurityConfig` 에 인가 규칙 추가**

`authorizeHttpRequests` 블록에서 `/api/account/**` 규칙 근처에 추가한다. **역할 게이트를 여기에 두지 않는다** — 법인카드의 권한은 전역 role(USER/MANAGER/ADMIN)이 아니라 조직 내 역할(OWNER/MANAGER/STAFF)이라 서비스 안에서 판정해야 한다.

```java
                        // 법인카드 — 인증만 요구하고, 조직 역할(OWNER/MANAGER/STAFF) 판정은
                        // card-service 의 CardOrgAuthorizer 가 멤버십 프로젝션으로 수행한다(IDOR 방지).
                        .requestMatchers("/api/cards/**").authenticated()
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew :shared-common:test`
Expected: PASS (기존 테스트 포함 전부)

- [ ] **Step 7: 커밋**

```bash
git add shared-common
git commit -m "feat(common): card 에러코드·감사액션·인가 규칙 추가"
```

---

## Task 2: account-service 재원 내부 API

**Files:**

- Create: `account-service/src/main/java/github/lms/lemuel/account/adapter/in/web/InternalAccountController.java`
- Modify: `account-service/src/main/java/github/lms/lemuel/account/application/port/out/LoadAccountEntryPort.java`
- Modify: `account-service/src/main/java/github/lms/lemuel/account/adapter/out/persistence/AccountEntryPersistenceAdapter.java` (구현 추가)
- Modify: `account-service/src/main/java/github/lms/lemuel/account/application/port/in/AccountQueryUseCase.java` + `application/service/AccountQueryService.java`
- Modify: `account-service/src/main/resources/application.yml`
- Create: `account-service/src/main/resources/application-prod.yml`
- Modify: `docker-compose.yml` (account-service 블록에 `INTERNAL_API_KEY`)
- Test: `account-service/src/test/java/github/lms/lemuel/account/adapter/in/web/InternalAccountControllerTest.java`
- Test: `account-service/src/test/java/github/lms/lemuel/account/integration/SellerFundingIT.java`

**Interfaces:**

- Consumes: Task 1 없음 (독립)
- Produces:
  - `GET /internal/account/sellers/{sellerId}/funding` → `{"sellerId":"123","sellerPayable":"170000.00","holdbackPayable":"10000.00"}` (구현은 `String sellerId` — Task 8 이 이 텍스트로 DTO 를 만들면 역직렬화가 어긋나므로 코드가 정본, 여기를 정정)
  - `LoadAccountEntryPort.balanceOf(OwnerType ownerType, String ownerId, GlAccount account) : BigDecimal`
  - **두 계정 잔액은 반드시 단일 SQL 문으로 읽는다** (리뷰 후 정정, 2026-08-01). `SELLER_PAYABLE` 과 `HOLDBACK_PAYABLE` 을 SELECT 두 번으로 나눠 읽으면 READ_COMMITTED 에서 문장마다 스냅샷이 갱신되므로, 그 사이 홀드백 해제(HOLDBACK_PAYABLE→SELLER_PAYABLE 재분류)가 커밋되면 합계가 일시적으로 어긋난다 — 이 API 의 존재 이유("카드 한도와 회계 장부가 어긋나지 않는다")와 정면으로 충돌한다. 같은 리포의 `AccountBalanceRepository#findBalanceReconRows` 가 동일 사유(감사 MED-3)로 이미 단일 문장을 채택했다. 잔액 행이 없는 계정은 `BigDecimal.ZERO` 로 정규화한다.
  - `AccountQueryUseCase.sellerFunding(String sellerId) : SellerFunding` — `record SellerFunding(String sellerId, BigDecimal sellerPayable, BigDecimal holdbackPayable)`

> **왜 account-service 를 고치는가:** 카드 재원 `F = Σconfirmed − Σpayout − Σholdback_consumed − Σrecovery_offset − Σwithholding` 는 account-service 가 이미 `SELLER_PAYABLE` + `HOLDBACK_PAYABLE` 잔액으로 유지하고 있다(실체화 테이블 `account_balances` + `BalanceReconScheduler` 정합 배치). card-service 가 같은 계산을 재구현하면 진실의 원천이 둘로 갈라진다.
>
> **주의 — 현재 account-service 는 `/internal/**`가 fail-open 이다.**`InternalApiKeyFilter`는`@Component`로 이미 로드돼 있으나`app.internal.api-key`프로퍼티도,`application-prod.yml`도, compose 의`INTERNAL_API_KEY` 도 없다. 세 곳을 함께 넣지 않으면 재원 API 가 무인증으로 노출된다.
>
> **`accountSummary` 경로를 쓰지 말 것.** 그 경로는 실체화 테이블이 아니라 원장 전표를 전량 로드해 도메인에서 재합산한다(O(셀러 전표 수)). 내부 API 핫패스에는 부적합하다.

- [ ] **Step 1: 실패 테스트 작성 — 컨트롤러**

`account-service/src/test/java/github/lms/lemuel/account/adapter/in/web/InternalAccountControllerTest.java`:

```java
package github.lms.lemuel.account.adapter.in.web;

import github.lms.lemuel.account.application.port.in.AccountQueryUseCase;
import github.lms.lemuel.account.application.port.in.SellerFundingQuery.SellerFunding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 내부 재원 API 응답 매핑 고정 — card-service 의 한도 산정 입력이므로 필드명·타입이 계약이다.
 * 금액은 JSON 문자열(DATA-STANDARD N5)로 나가야 한다 — JS Number 로 정밀도가 깎이면 한도가 틀어진다.
 */
@ExtendWith(MockitoExtension.class)
class InternalAccountControllerTest {

    @Mock AccountQueryUseCase accountQueryUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new InternalAccountController(accountQueryUseCase))
                .build();
    }

    @Test
    @DisplayName("셀러 재원은 SELLER_PAYABLE·HOLDBACK_PAYABLE 두 잔액을 문자열로 반환한다")
    void sellerFundingReturnsTwoBalancesAsStrings() throws Exception {
        when(accountQueryUseCase.sellerFunding("777")).thenReturn(
                new SellerFunding("777", new BigDecimal("170000.00"), new BigDecimal("10000.00")));

        mockMvc.perform(get("/internal/account/sellers/777/funding"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerId").value("777"))
                .andExpect(jsonPath("$.sellerPayable").value("170000.00"))
                .andExpect(jsonPath("$.holdbackPayable").value("10000.00"));
    }

    @Test
    @DisplayName("잔액 행이 없는 셀러는 0 — null 을 노출하지 않는다")
    void unknownSellerReturnsZeros() throws Exception {
        when(accountQueryUseCase.sellerFunding("999")).thenReturn(
                new SellerFunding("999", BigDecimal.ZERO, BigDecimal.ZERO));

        mockMvc.perform(get("/internal/account/sellers/999/funding"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerPayable").value("0"))
                .andExpect(jsonPath("$.holdbackPayable").value("0"));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :account-service:test --tests '*InternalAccountControllerTest'`
Expected: 컴파일 실패 — `InternalAccountController` / `SellerFundingQuery` 없음.

- [ ] **Step 3: 포트에 계정별 잔액 조회 추가**

`LoadAccountEntryPort.java` 에 메서드 추가:

```java
    /**
     * owner 의 특정 계정 잔액(정상방향 순잔액)을 실체화 테이블에서 읽는다.
     *
     * <p>기존 {@code sellerPayableBalance} 는 SELLER_PAYABLE 하나로 하드코딩돼 있어 재원 조회
     * (SELLER_PAYABLE + HOLDBACK_PAYABLE)에 쓸 수 없다. 잔액 행이 없으면 {@code BigDecimal.ZERO}.
     */
    BigDecimal balanceOf(OwnerType ownerType, String ownerId, GlAccount account);
```

`AccountEntryPersistenceAdapter` 구현 — `AccountBalanceRepository.findByOwnerTypeAndOwnerIdAndAccount` 는 이미 범용이므로 위임만 한다:

```java
    @Override
    public BigDecimal balanceOf(OwnerType ownerType, String ownerId, GlAccount account) {
        return accountBalanceRepository
                .findByOwnerTypeAndOwnerIdAndAccount(ownerType, ownerId, account)
                .map(AccountBalanceJpaEntity::getBalance)
                .orElse(BigDecimal.ZERO);
    }
```

- [ ] **Step 4: 조회 유스케이스 추가**

`application/port/in/SellerFundingQuery.java` (신규):

```java
package github.lms.lemuel.account.application.port.in;

import java.math.BigDecimal;

/**
 * 셀러 재원 조회 계약 — card-service 가 법인카드 한도를 산정할 때 쓰는 입력.
 *
 * <p>재원 = 확정됐지만 아직 셀러에게 나가지 않은 정산금 + 홀드백 유보분
 *        = SELLER_PAYABLE 잔액 + HOLDBACK_PAYABLE 잔액.
 * 이 등식이 성립하는 이유는 account 가 settlement 의 confirmed/payout/holdback_consumed/
 * recovery_offset/withholding 을 모두 소비해 두 계정에 반영하기 때문이다.
 */
public interface SellerFundingQuery {

    SellerFunding sellerFunding(String sellerId);

    record SellerFunding(String sellerId, BigDecimal sellerPayable, BigDecimal holdbackPayable) {
    }
}
```

`AccountQueryUseCase` 가 `SellerFundingQuery` 를 상속하도록 하고, `AccountQueryService` 에 구현:

```java
    @Override
    public SellerFunding sellerFunding(String sellerId) {
        return new SellerFunding(
                sellerId,
                loadAccountEntryPort.balanceOf(OwnerType.SELLER, sellerId, GlAccount.SELLER_PAYABLE),
                loadAccountEntryPort.balanceOf(OwnerType.SELLER, sellerId, GlAccount.HOLDBACK_PAYABLE));
    }
```

- [ ] **Step 5: 내부 컨트롤러 작성**

order-service `InternalReconController` 패턴을 따른다 — 권한 어노테이션 없음(인증은 `InternalApiKeyFilter` 전담), 응답 DTO 는 내부 `record`.

```java
package github.lms.lemuel.account.adapter.in.web;

import github.lms.lemuel.account.application.port.in.AccountQueryUseCase;
import github.lms.lemuel.account.application.port.in.SellerFundingQuery.SellerFunding;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * account 가 자기 소유 GL 잔액을 내부 소비자에게 노출하는 API.
 *
 * <p>card-service 가 법인카드 한도를 산정할 때 셀러 재원을 여기서 조회한다. 같은 계산을
 * card 가 재구현하면 이벤트 유실 시 카드 한도와 회계 장부가 조용히 어긋나므로, 진실의 원천을
 * 회계 쪽 하나로 둔다 — 특정 셀러의 한도 근거를 시산표로 설명할 수 있게 된다.
 *
 * <p>인증은 shared-common {@code InternalApiKeyFilter}(X-Internal-Api-Key) 가 담당한다.
 * 운영에서는 {@code app.security.internal-key-required=true}(application-prod.yml)로 fail-closed.
 *
 * <p>★ account 는 소비 전용 서비스다 — 여기에 이벤트 발행 코드를 넣지 않는다(하드스톱).
 */
@Tag(name = "Internal - Account", description = "account 자기 GL 잔액 노출 (card-service 가 소비)")
@RestController
@RequestMapping("/internal/account")
public class InternalAccountController {

    private final AccountQueryUseCase accountQueryUseCase;

    public InternalAccountController(AccountQueryUseCase accountQueryUseCase) {
        this.accountQueryUseCase = accountQueryUseCase;
    }

    @Operation(summary = "셀러 재원 잔액 (account 원천)",
            description = "SELLER_PAYABLE(확정·미지급 정산금) + HOLDBACK_PAYABLE(유보분) 실체화 잔액. "
                    + "금액은 JSON 문자열(DATA-STANDARD N5). 잔액 행이 없으면 0.")
    @GetMapping("/sellers/{sellerId}/funding")
    public FundingResponse sellerFunding(@PathVariable String sellerId) {
        SellerFunding funding = accountQueryUseCase.sellerFunding(sellerId);
        return new FundingResponse(
                funding.sellerId(),
                funding.sellerPayable().toPlainString(),
                funding.holdbackPayable().toPlainString());
    }

    /** 금액은 문자열 — JS Number 변환으로 정밀도가 깎이면 한도가 틀어진다. */
    public record FundingResponse(String sellerId, String sellerPayable, String holdbackPayable) {
    }
}
```

- [ ] **Step 6: 내부 API 키 배선 3곳**

`account-service/src/main/resources/application.yml` 의 `app:` 블록에 추가:

```yaml
# 내부 API(/internal/**) 공유 시크릿 — InternalApiKeyFilter 가 X-Internal-Api-Key 를 검증.
# card-service(AccountFundingAdapter)의 INTERNAL_API_KEY 와 동일해야 한다. 미설정 시 검증 비활성(개발)+경고.
internal:
  api-key: ${INTERNAL_API_KEY:}
```

`account-service/src/main/resources/application-prod.yml` (신규):

```yaml
# 운영(prod) 프로파일 — SPRING_PROFILES_ACTIVE=prod 로 활성.
#
# account-service 는 /internal/account/** 로 셀러 재원 잔액을 노출한다(card-service 소비).
# 기본 프로파일은 키 미설정 시 fail-open(경고 후 통과)으로 개발 편의를 보존하지만, 운영에서는
# fail-closed 로 전환한다 — INTERNAL_API_KEY 가 없으면 401 로 거부한다(InternalApiKeyFilter).
# 재원 잔액은 여신 판단의 입력이라 무방비 노출이 곧 한도 조작 경로가 된다.
# (order/settlement/loan/investment 의 application-prod.yml 과 동형)
app:
  security:
    internal-key-required: true
```

`docker-compose.yml` 의 `account-service` 블록 `environment:` 에 추가:

```yaml
INTERNAL_API_KEY: ${INTERNAL_API_KEY:-lemuel-internal-dev-key}
```

- [ ] **Step 7: 컨트롤러 테스트 통과 확인**

Run: `./gradlew :account-service:test --tests '*InternalAccountControllerTest'`
Expected: PASS

- [ ] **Step 8: 실 DB 통합 테스트 작성**

`account-service/src/test/java/github/lms/lemuel/account/integration/SellerFundingIT.java` — `MaterializedBalanceIT` 의 컨테이너·게이트 패턴을 그대로 쓴다.

```java
package github.lms.lemuel.account.integration;

import github.lms.lemuel.AccountServiceApplication;
import github.lms.lemuel.account.application.port.in.AccountQueryUseCase;
import github.lms.lemuel.account.application.port.in.SellerFundingQuery.SellerFunding;
import github.lms.lemuel.account.application.port.in.RecordAccountEntryUseCase;
import github.lms.lemuel.account.domain.AccountEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 셀러 재원 조회가 실체화 잔액과 일치하는지 실 PG 로 증명한다.
 *
 * <p>card-service 의 한도는 이 값에서 직접 유도되므로, 여기서 어긋나면 한도가 통째로 틀린다.
 * 특히 홀드백 해제(재분류)가 총 재원을 바꾸지 않아야 한다는 점을 고정한다 —
 * 해제는 HOLDBACK_PAYABLE → SELLER_PAYABLE 이동일 뿐 합계 불변이다.
 */
@SpringBootTest(
        classes = AccountServiceApplication.class,
        properties = {
                "app.kafka.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class SellerFundingIT {

    static boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    @Container
    static final PostgreSQLContainer<?> ACCOUNT_DB = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("lemuel_account").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", ACCOUNT_DB::getJdbcUrl);
        r.add("spring.datasource.username", ACCOUNT_DB::getUsername);
        r.add("spring.datasource.password", ACCOUNT_DB::getPassword);
        r.add("POSTGRES_USER", ACCOUNT_DB::getUsername);
        r.add("POSTGRES_PASSWORD", ACCOUNT_DB::getPassword);
    }

    @Autowired RecordAccountEntryUseCase recordAccountEntryUseCase;
    @Autowired AccountQueryUseCase accountQueryUseCase;

    @Test
    @DisplayName("재원 = SELLER_PAYABLE + HOLDBACK_PAYABLE, 홀드백 해제는 총액을 바꾸지 않는다")
    void fundingIsPayablePlusHoldback_andReleaseIsTotalNeutral() {
        String seller = "940001";
        recordAccountEntryUseCase.record(
                AccountEntry.settlementCreatedImmediate(seller, "S1", new BigDecimal("100000")));
        recordAccountEntryUseCase.record(
                AccountEntry.settlementHoldbackRecognized(seller, "S1", new BigDecimal("30000")));

        SellerFunding before = accountQueryUseCase.sellerFunding(seller);
        assertThat(before.sellerPayable()).isEqualByComparingTo("100000");
        assertThat(before.holdbackPayable()).isEqualByComparingTo("30000");
        BigDecimal totalBefore = before.sellerPayable().add(before.holdbackPayable());

        // 유보 해제 — 지급 가능으로 재분류될 뿐 총 재원은 그대로여야 한다.
        recordAccountEntryUseCase.record(
                AccountEntry.holdbackReleased(seller, "S1", new BigDecimal("20000")));

        SellerFunding after = accountQueryUseCase.sellerFunding(seller);
        assertThat(after.sellerPayable()).isEqualByComparingTo("120000");
        assertThat(after.holdbackPayable()).isEqualByComparingTo("10000");
        assertThat(after.sellerPayable().add(after.holdbackPayable()))
                .isEqualByComparingTo(totalBefore);
    }

    @Test
    @DisplayName("실지급은 재원을 줄인다")
    void payoutReducesFunding() {
        String seller = "940002";
        recordAccountEntryUseCase.record(
                AccountEntry.settlementCreatedImmediate(seller, "S9", new BigDecimal("80000")));
        recordAccountEntryUseCase.record(
                AccountEntry.payoutCompleted(seller, "P9", new BigDecimal("50000")));

        SellerFunding funding = accountQueryUseCase.sellerFunding(seller);
        assertThat(funding.sellerPayable()).isEqualByComparingTo("30000");
    }

    @Test
    @DisplayName("잔액 행이 없는 셀러는 0")
    void unknownSellerIsZero() {
        SellerFunding funding = accountQueryUseCase.sellerFunding("940099");
        assertThat(funding.sellerPayable()).isEqualByComparingTo("0");
        assertThat(funding.holdbackPayable()).isEqualByComparingTo("0");
    }
}
```

- [ ] **Step 9: 전체 통과 + 커버리지 확인**

Run: `./gradlew :account-service:test :account-service:jacocoTestCoverageVerification`
Expected: PASS

- [ ] **Step 10: 커밋**

```bash
git add account-service docker-compose.yml
git commit -m "feat(account): 셀러 재원 내부 조회 API + 내부 키 fail-closed 배선"
```

---

## Task 3: organization 이벤트 2종 신설

**Files:**

- Create: `shared-common/src/testFixtures/resources/contracts/events/lemuel.organization.member_role_changed.schema.json`
- Create: `shared-common/src/testFixtures/resources/contracts/events/lemuel.organization.member_removed.schema.json`
- Create: `shared-common/src/testFixtures/resources/contracts/events/samples/lemuel.organization.member_role_changed.sample.json`
- Create: `shared-common/src/testFixtures/resources/contracts/events/samples/lemuel.organization.member_removed.sample.json`
- Modify: `organization-service/src/main/java/github/lms/lemuel/organization/application/port/out/PublishOrganizationEventPort.java`
- Modify: `organization-service/src/main/java/github/lms/lemuel/organization/adapter/out/event/OrganizationEventPublisherAdapter.java`
- Modify: `organization-service/src/main/java/github/lms/lemuel/organization/application/service/MembershipCommandService.java`
- Modify: `organization-service/src/test/java/github/lms/lemuel/organization/adapter/out/event/OrganizationEventContractTest.java`
- Modify: `organization-service/src/test/java/github/lms/lemuel/organization/application/service/MembershipCommandServiceTest.java`

**Interfaces:**

- Consumes: 없음
- Produces:
  - 토픽 `lemuel.organization.member_role_changed` — `{organizationId:int, userId:int, membershipId:int, previousRole:str, newRole:str}`
  - 토픽 `lemuel.organization.member_removed` — `{organizationId:int, userId:int, membershipId:int}`
  - `PublishOrganizationEventPort.publishMemberRoleChanged(Membership membership, OrgRole previousRole)` / `publishMemberRemoved(Membership membership)`

> **왜 필요한가:** organization-service 는 현재 `created` 와 `member_joined` 만 발행한다. 역할 변경·멤버 제거에 이벤트가 없어, card-service 가 멤버 프로젝션을 만들면 **조직에서 제거된 임직원의 카드가 유효한 상태로 남는다.** 법인카드에서 이는 사고다. Outbox 배선은 이미 있으므로 이벤트 정의와 발행 지점만 추가한다.

- [ ] **Step 1: 계약 스키마 2개 작성**

`lemuel.organization.member_removed.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "lemuel.organization.member_removed",
  "description": "organization-service(OrganizationEventPublisherAdapter) → card-service. 멤버십이 REMOVED(터미널)로 전이되면 발행된다. 소비측은 해당 임직원의 카드를 SUSPENDED 로 전이해야 한다 — 이 이벤트가 없으면 퇴사자 카드가 유효한 채로 남는다.",
  "type": "object",
  "properties": {
    "organizationId": { "type": "integer" },
    "userId": { "type": "integer" },
    "membershipId": { "type": "integer" }
  },
  "required": ["organizationId", "userId", "membershipId"],
  "additionalProperties": true
}
```

`lemuel.organization.member_role_changed.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "lemuel.organization.member_role_changed",
  "description": "organization-service(OrganizationEventPublisherAdapter) → card-service. 활성 멤버의 역할이 변경되면 발행된다. previousRole/newRole 은 OWNER|MANAGER|STAFF. 소비측은 권한 판정용 멤버 프로젝션의 역할을 갱신한다.",
  "type": "object",
  "properties": {
    "organizationId": { "type": "integer" },
    "userId": { "type": "integer" },
    "membershipId": { "type": "integer" },
    "previousRole": { "type": "string", "enum": ["OWNER", "MANAGER", "STAFF"] },
    "newRole": { "type": "string", "enum": ["OWNER", "MANAGER", "STAFF"] }
  },
  "required": [
    "organizationId",
    "userId",
    "membershipId",
    "previousRole",
    "newRole"
  ],
  "additionalProperties": true
}
```

정본 샘플 (`samples/` 아래, 스키마를 실제로 통과해야 함):

```json
{ "organizationId": 3001, "userId": 888, "membershipId": 9001 }
```

```json
{
  "organizationId": 3001,
  "userId": 888,
  "membershipId": 9001,
  "previousRole": "STAFF",
  "newRole": "MANAGER"
}
```

- [ ] **Step 2: 실패 테스트 작성 — 프로듀서 계약**

`OrganizationEventContractTest` 에 테스트 2개 추가:

```java
    @Test
    @DisplayName("MemberRemoved 페이로드는 lemuel.organization.member_removed 계약을 만족한다")
    void memberRemoved_satisfiesContract() {
        Membership membership = Membership.builder()
                .id(9001L)
                .organizationId(3001L)
                .userId(888L)
                .role(OrgRole.STAFF)
                .status(MembershipStatus.REMOVED)
                .invitedBy(777L)
                .build();

        publisher.publishMemberRemoved(membership);

        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        EventContractValidator.assertValid(
                "lemuel.organization.member_removed", outboxCaptor.getValue().getPayload());
    }

    @Test
    @DisplayName("MemberRoleChanged 페이로드는 이전/신규 역할을 모두 싣는다")
    void memberRoleChanged_satisfiesContract() {
        Membership membership = Membership.builder()
                .id(9001L)
                .organizationId(3001L)
                .userId(888L)
                .role(OrgRole.MANAGER)
                .status(MembershipStatus.ACTIVE)
                .invitedBy(777L)
                .build();

        publisher.publishMemberRoleChanged(membership, OrgRole.STAFF);

        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        String payload = outboxCaptor.getValue().getPayload();
        EventContractValidator.assertValid("lemuel.organization.member_role_changed", payload);
        assertThat(payload).contains("\"previousRole\":\"STAFF\"").contains("\"newRole\":\"MANAGER\"");
    }
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :organization-service:test --tests '*OrganizationEventContractTest'`
Expected: 컴파일 실패 — `publishMemberRemoved` 없음.

- [ ] **Step 4: 포트 + 어댑터 구현**

`PublishOrganizationEventPort` 에 메서드 2개 추가하고, 어댑터에 구현:

```java
    @Override
    public void publishMemberRoleChanged(Membership membership, OrgRole previousRole) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", membership.getOrganizationId());
        payload.put("userId", membership.getUserId());
        payload.put("membershipId", membership.getId());
        payload.put("previousRole", previousRole.name());
        payload.put("newRole", membership.getRole().name());
        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE,
                String.valueOf(membership.getOrganizationId()),
                "OrganizationMemberRoleChanged",
                toJson(payload)));
    }

    @Override
    public void publishMemberRemoved(Membership membership) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", membership.getOrganizationId());
        payload.put("userId", membership.getUserId());
        payload.put("membershipId", membership.getId());
        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE,
                String.valueOf(membership.getOrganizationId()),
                "OrganizationMemberRemoved",
                toJson(payload)));
    }
```

- [ ] **Step 5: 서비스에서 발행 호출 — 실패 테스트 먼저**

`MembershipCommandServiceTest` 에 추가:

```java
    @Test
    @DisplayName("역할 변경 성공 시 member_role_changed 를 이전 역할과 함께 발행한다")
    void changeRole_publishesEventWithPreviousRole() {
        when(loadMembership.findActiveMember(1L, 100L)).thenReturn(Optional.of(activeMember(100L, OrgRole.OWNER)));
        when(loadMembership.countActiveOwners(1L)).thenReturn(2L);

        service.changeRole(new ChangeRoleCommand(1L, 100L, OrgRole.MANAGER, 100L));

        verify(publish).publishMemberRoleChanged(any(), eq(OrgRole.OWNER));
    }

    @Test
    @DisplayName("멤버 제거 성공 시 member_removed 를 발행한다 — 소비측이 카드를 정지시킨다")
    void remove_publishesEvent() {
        Membership staff = activeMember(200L, OrgRole.STAFF);
        when(loadMembership.findSlotOccupant(1L, 200L)).thenReturn(Optional.of(staff));

        service.remove(1L, 200L, 100L);

        verify(publish).publishMemberRemoved(staff);
    }

    @Test
    @DisplayName("마지막 OWNER 제거가 차단되면 이벤트를 발행하지 않는다")
    void remove_blocked_publishesNothing() {
        when(loadMembership.findSlotOccupant(1L, 100L)).thenReturn(Optional.of(activeMember(100L, OrgRole.OWNER)));
        when(loadMembership.countActiveOwners(1L)).thenReturn(1L);

        assertThatThrownBy(() -> service.remove(1L, 100L, 100L))
                .isInstanceOf(LastOwnerException.class);
        verify(publish, never()).publishMemberRemoved(any());
    }
```

- [ ] **Step 6: 실패 확인 → 서비스 구현**

Run: `./gradlew :organization-service:test --tests '*MembershipCommandServiceTest'`
Expected: FAIL — `publishMemberRoleChanged` 미호출.

`MembershipCommandService.changeRole` 에서 역할 변경 **직전에 이전 역할을 포착**하고, 저장 후 발행한다. `remove` 는 저장 후 발행한다. **발행은 도메인 트랜잭션과 같은 트랜잭션 안**이어야 원자성이 유지된다(Outbox 전제).

- [ ] **Step 7: 통과 확인**

Run: `./gradlew :organization-service:test :organization-service:jacocoTestCoverageVerification`
Expected: PASS

- [ ] **Step 8: 기존 소비자 영향 확인**

Run: `grep -rn "lemuel.organization" --include='*.java' --include='*.yml' . | grep -v organization-service`
Expected: card-service 외 소비자 없음(현재 organization 이벤트는 소비처 미배선). 있으면 그 서비스의 컨슈머 계약 테스트도 실행할 것.

- [ ] **Step 9: 커밋**

```bash
git add shared-common/src/testFixtures organization-service
git commit -m "feat(organization): 멤버 역할변경·제거 이벤트 발행 — 소비측 프로젝션 부패 방지"
```

---

## Task 4: 카드 도메인 애그리거트

**Files:**

- Create: `card-service/src/main/java/github/lms/lemuel/card/domain/CardAccountStatus.java`
- Create: `card-service/src/main/java/github/lms/lemuel/card/domain/CardStatus.java`
- Create: `card-service/src/main/java/github/lms/lemuel/card/domain/OrgRole.java`
- Create: `card-service/src/main/java/github/lms/lemuel/card/domain/ReputationGrade.java`
- Create: `card-service/src/main/java/github/lms/lemuel/card/domain/LimitSnapshot.java`
- Create: `card-service/src/main/java/github/lms/lemuel/card/domain/LimitChangeResult.java`
- Create: `card-service/src/main/java/github/lms/lemuel/card/domain/CardAccount.java`
- Create: `card-service/src/main/java/github/lms/lemuel/card/domain/Card.java`
- Create: `card-service/src/main/java/github/lms/lemuel/card/domain/exception/InvalidCardTransitionException.java`
- Create: `card-service/src/main/java/github/lms/lemuel/card/domain/exception/SubLimitExceededException.java`
- Test: `card-service/src/test/java/github/lms/lemuel/card/domain/CardAccountTest.java`
- Test: `card-service/src/test/java/github/lms/lemuel/card/domain/CardTest.java`

**Interfaces:**

- Consumes: Task 0 의 모듈
- Produces:
  - `CardAccountStatus` = `SCREENING, ACTIVE, SUSPENDED, CLOSED, REJECTED` + `canTransitionTo(CardAccountStatus)`
  - `CardStatus` = `ISSUED, SUSPENDED, CANCELED` + `canTransitionTo(CardStatus)`
  - `CardAccount.open(Long organizationId, String sellerId) : CardAccount` (정적 팩토리, SCREENING) + `activate(BigDecimal masterLimit, LimitSnapshot)` · `reject(String reason)` · `suspend()` · `resume()` · `close()` · `changeMasterLimit(BigDecimal newLimit, BigDecimal currentSubLimitSum) : LimitChangeResult` · `assertCanIssue(BigDecimal currentSubLimitSum, BigDecimal newSubLimit)` · getter `getId/getOrganizationId/getSellerId/getStatus/getMasterLimit/getLimitSnapshot/getVersion`
  - `Card.issue(Long cardAccountId, Long holderUserId, String maskedCardNo, BigDecimal subLimit) : Card` + `changeSubLimit(BigDecimal)` · `suspend()`(멱등) · `resume()` · `cancel()` · getter `getId/getCardAccountId/getHolderUserId/getMaskedCardNo/getSubLimit/getStatus/getVersion`
  - `record LimitChangeResult(BigDecimal appliedLimit, boolean clamped)`
  - `record LimitSnapshot(BigDecimal sellerPayable, BigDecimal holdbackPayable, BigDecimal appliedRatio, ReputationGrade reputationGrade, String formula)` + `funding()`
  - `enum ReputationGrade { A, B, C, D, E }` + `haircut()` · `unknownDefault()`
  - **`enum OrgRole { OWNER, MANAGER, STAFF }`** — card-service **자체** enum 이다. ArchUnit 이 `github.lms.lemuel.organization..` 의존을 금지하므로 organization-service 의 것을 import 할 수 없다. 이벤트 페이로드의 문자열로만 연결된다.

- [ ] **Step 1: 상태머신 실패 테스트 작성**

`CardAccountTest.java` — 상태머신은 정상 전이만이 아니라 **비정상 전이 차단**을 반드시 검증한다.

```java
package github.lms.lemuel.card.domain;

import github.lms.lemuel.card.domain.exception.InvalidCardTransitionException;
import github.lms.lemuel.card.domain.exception.SubLimitExceededException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardAccountTest {

    private static CardAccount screening() {
        return CardAccount.open(3001L, "777");
    }

    private static LimitSnapshot snapshot() {
        return new LimitSnapshot(
                new BigDecimal("200000.00"), new BigDecimal("50000.00"),
                new BigDecimal("0.70"), ReputationGrade.B, "floor(F x R x H)");
    }

    @Test
    @DisplayName("개설 직후는 SCREENING 이고 마스터 한도는 0")
    void opensInScreening() {
        CardAccount account = screening();
        assertThat(account.getStatus()).isEqualTo(CardAccountStatus.SCREENING);
        assertThat(account.getMasterLimit()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("심사 통과 → ACTIVE + 한도·근거 스냅샷 보존")
    void activateSetsLimitAndSnapshot() {
        CardAccount account = screening();

        account.activate(new BigDecimal("175000"), snapshot());

        assertThat(account.getStatus()).isEqualTo(CardAccountStatus.ACTIVE);
        assertThat(account.getMasterLimit()).isEqualByComparingTo("175000");
        assertThat(account.getLimitSnapshot().reputationGrade()).isEqualTo(ReputationGrade.B);
    }

    @Test
    @DisplayName("REJECTED 는 터미널 — 어떤 전이도 불가")
    void rejectedIsTerminal() {
        CardAccount account = screening();
        account.reject();

        assertThat(account.getStatus()).isEqualTo(CardAccountStatus.REJECTED);
        assertThatThrownBy(() -> account.activate(new BigDecimal("100000"), snapshot()))
                .isInstanceOf(InvalidCardTransitionException.class);
        assertThatThrownBy(account::suspend).isInstanceOf(InvalidCardTransitionException.class);
    }

    @Test
    @DisplayName("CLOSED 는 터미널 — 재활성 불가")
    void closedIsTerminal() {
        CardAccount account = screening();
        account.activate(new BigDecimal("100000"), snapshot());
        account.close();

        assertThatThrownBy(account::suspend).isInstanceOf(InvalidCardTransitionException.class);
        assertThatThrownBy(() -> account.changeMasterLimit(new BigDecimal("200000"), BigDecimal.ZERO))
                .isInstanceOf(InvalidCardTransitionException.class);
    }

    @Test
    @DisplayName("ACTIVE ⇄ SUSPENDED 는 왕복 가능")
    void activeSuspendedRoundTrip() {
        CardAccount account = screening();
        account.activate(new BigDecimal("100000"), snapshot());

        account.suspend();
        assertThat(account.getStatus()).isEqualTo(CardAccountStatus.SUSPENDED);
        account.resume();
        assertThat(account.getStatus()).isEqualTo(CardAccountStatus.ACTIVE);
    }

    // ── 불변식: masterLimit >= Σ subLimit ──

    @Test
    @DisplayName("서브한도 합계가 마스터 한도를 넘으면 발급 거부")
    void issueRejectedWhenSumExceedsMaster() {
        CardAccount account = screening();
        account.activate(new BigDecimal("100000"), snapshot());

        assertThatThrownBy(() ->
                account.assertCanIssue(new BigDecimal("90000"), new BigDecimal("20000")))
                .isInstanceOf(SubLimitExceededException.class);
    }

    @Test
    @DisplayName("합계가 마스터 한도와 정확히 같으면 허용 — 경계값")
    void issueAllowedAtExactBoundary() {
        CardAccount account = screening();
        account.activate(new BigDecimal("100000"), snapshot());

        account.assertCanIssue(new BigDecimal("90000"), new BigDecimal("10000"));  // 예외 없음
    }

    @Test
    @DisplayName("ACTIVE 가 아니면 발급 불가")
    void issueRejectedWhenNotActive() {
        CardAccount account = screening();
        account.activate(new BigDecimal("100000"), snapshot());
        account.suspend();

        assertThatThrownBy(() -> account.assertCanIssue(BigDecimal.ZERO, new BigDecimal("1000")))
                .isInstanceOf(InvalidCardTransitionException.class);
    }

    // ── 한도 하향 클램프 ──

    @Test
    @DisplayName("상향은 그대로 반영")
    void raiseAppliesDirectly() {
        CardAccount account = screening();
        account.activate(new BigDecimal("100000"), snapshot());

        LimitChangeResult result = account.changeMasterLimit(new BigDecimal("150000"), new BigDecimal("80000"));

        assertThat(result.appliedLimit()).isEqualByComparingTo("150000");
        assertThat(result.clamped()).isFalse();
        assertThat(account.getMasterLimit()).isEqualByComparingTo("150000");
    }

    @Test
    @DisplayName("하향이 서브한도 합계보다 낮으면 합계까지만 내린다 — 발급된 카드를 조용히 죽이지 않는다")
    void lowerClampsToSubLimitSum() {
        CardAccount account = screening();
        account.activate(new BigDecimal("100000"), snapshot());

        LimitChangeResult result = account.changeMasterLimit(new BigDecimal("50000"), new BigDecimal("80000"));

        assertThat(result.appliedLimit()).isEqualByComparingTo("80000");
        assertThat(result.clamped()).isTrue();
        assertThat(account.getMasterLimit()).isEqualByComparingTo("80000");
    }

    @Test
    @DisplayName("하향이 서브한도 합계 이상이면 클램프하지 않는다 — 경계값")
    void lowerNotClampedWhenAboveSum() {
        CardAccount account = screening();
        account.activate(new BigDecimal("100000"), snapshot());

        LimitChangeResult result = account.changeMasterLimit(new BigDecimal("80000"), new BigDecimal("80000"));

        assertThat(result.appliedLimit()).isEqualByComparingTo("80000");
        assertThat(result.clamped()).isFalse();
    }

    @Test
    @DisplayName("음수 한도는 거부")
    void negativeLimitRejected() {
        CardAccount account = screening();
        account.activate(new BigDecimal("100000"), snapshot());

        assertThatThrownBy(() -> account.changeMasterLimit(new BigDecimal("-1"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :card-service:test --tests '*CardAccountTest'`
Expected: 컴파일 실패 — 도메인 클래스 없음.

- [ ] **Step 3: 상태 enum 구현**

```java
package github.lms.lemuel.card.domain;

import java.util.Set;

/**
 * 법인 카드계정 상태머신.
 *
 * <p>SCREENING → ACTIVE ⇄ SUSPENDED → CLOSED. 심사 탈락은 REJECTED(터미널).
 * REJECTED·CLOSED 에서 나가는 전이는 없다 — 재신청은 새 카드계정이다.
 */
public enum CardAccountStatus {

    SCREENING,
    ACTIVE,
    SUSPENDED,
    CLOSED,
    REJECTED;

    private static final java.util.Map<CardAccountStatus, Set<CardAccountStatus>> ALLOWED =
            java.util.Map.of(
                    SCREENING, Set.of(ACTIVE, REJECTED),
                    ACTIVE, Set.of(SUSPENDED, CLOSED),
                    SUSPENDED, Set.of(ACTIVE, CLOSED),
                    CLOSED, Set.of(),
                    REJECTED, Set.of());

    public boolean canTransitionTo(CardAccountStatus target) {
        return ALLOWED.get(this).contains(target);
    }
}
```

`CardStatus` 도 동형으로: `ISSUED → SUSPENDED`, `SUSPENDED → ISSUED`, 양쪽 다 `→ CANCELED`, `CANCELED` 는 빈 집합.

- [ ] **Step 4: `LimitSnapshot` · `LimitChangeResult` · `ReputationGrade` 작성**

```java
package github.lms.lemuel.card.domain;

import java.math.BigDecimal;

/**
 * 한도 산정 근거 스냅샷 — 사후에 "왜 이 한도였나"를 재현하기 위해 보존한다
 * (loan-service 가 신청 시점 신용점수·등급을 보존하는 것과 같은 이유).
 */
public record LimitSnapshot(BigDecimal sellerPayable,
                            BigDecimal holdbackPayable,
                            BigDecimal appliedRatio,
                            ReputationGrade reputationGrade,
                            String formula) {

    public LimitSnapshot {
        if (sellerPayable == null || holdbackPayable == null || appliedRatio == null
                || reputationGrade == null) {
            throw new IllegalArgumentException("한도 산정 근거는 전부 필수다 — 근거 없는 한도를 남기지 않는다");
        }
    }

    /** 재원 F = 확정·미지급 정산금 + 홀드백 유보분. */
    public BigDecimal funding() {
        return sellerPayable.add(holdbackPayable);
    }
}
```

```java
package github.lms.lemuel.card.domain;

import java.math.BigDecimal;

/**
 * 마스터 한도 변경 결과. {@code clamped} 는 하향이 Σ서브한도 하한에 걸려 잘렸음을 뜻하며,
 * limit_changed 이벤트에 실려 나간다 — 운영자가 "왜 요청한 만큼 안 내려갔나"를 알 수 있어야 한다.
 */
public record LimitChangeResult(BigDecimal appliedLimit, boolean clamped) {
}
```

```java
package github.lms.lemuel.card.domain;

import java.math.BigDecimal;

/**
 * 평판 등급별 haircut 계수. loan-service CreditPolicy 와 같은 축을 쓴다.
 * E 는 0.0 — 계수 곱의 결과가 0 이 되어 심사에서 탈락한다(별도 분기 없이 산식이 걸러낸다).
 */
public enum ReputationGrade {

    A(new BigDecimal("1.00")),
    B(new BigDecimal("1.00")),
    C(new BigDecimal("0.85")),
    D(new BigDecimal("0.70")),
    E(BigDecimal.ZERO);

    private final BigDecimal haircut;

    ReputationGrade(BigDecimal haircut) {
        this.haircut = haircut;
    }

    public BigDecimal haircut() {
        return haircut;
    }

    /** 프로젝션에 평판이 아직 없는 조직은 가장 보수적인 등급으로 본다. */
    public static ReputationGrade unknownDefault() {
        return D;
    }
}
```

- [ ] **Step 5: `CardAccount` 구현**

핵심만 발췌(전체는 위 테스트를 통과시키는 최소 구현):

```java
    /** 발급 가능 여부 검증 — 불변식 masterLimit >= Σ subLimit 의 도메인 표현. */
    public void assertCanIssue(BigDecimal currentSubLimitSum, BigDecimal newSubLimit) {
        if (status != CardAccountStatus.ACTIVE) {
            throw new InvalidCardTransitionException(
                    "ACTIVE 카드계정만 카드를 발급할 수 있습니다. 현재=" + status);
        }
        requireNonNegative(newSubLimit);
        BigDecimal after = currentSubLimitSum.add(newSubLimit);
        if (after.compareTo(masterLimit) > 0) {
            throw new SubLimitExceededException(masterLimit, currentSubLimitSum, newSubLimit);
        }
    }

    /**
     * 마스터 한도 변경. 상향은 그대로, 하향은 Σ서브한도를 하한으로 클램프한다.
     *
     * <p>이미 배분한 임직원 한도 아래로 마스터를 내리면 카드가 사전 통지 없이 무력화된다.
     * 재산정이 자동으로 도는 경로라 특히 위험해서, 도메인에서 하한을 강제한다.
     */
    public LimitChangeResult changeMasterLimit(BigDecimal newLimit, BigDecimal currentSubLimitSum) {
        requireMutable();
        requireNonNegative(newLimit);
        boolean clamped = newLimit.compareTo(currentSubLimitSum) < 0;
        BigDecimal applied = clamped ? currentSubLimitSum : newLimit;
        this.masterLimit = applied;
        return new LimitChangeResult(applied, clamped);
    }
```

- [ ] **Step 6: `CardTest` 작성 → 실패 확인 → `Card` 구현**

```java
    @Test
    @DisplayName("이미 CANCELED 인 카드는 어떤 전이도 불가 — 터미널")
    void canceledIsTerminal() {
        Card card = Card.issue(1L, 888L, "1234-****-****-5678", new BigDecimal("500000"));
        card.cancel();

        assertThatThrownBy(card::suspend).isInstanceOf(InvalidCardTransitionException.class);
        assertThatThrownBy(() -> card.changeSubLimit(new BigDecimal("100000")))
                .isInstanceOf(InvalidCardTransitionException.class);
    }

    @Test
    @DisplayName("이미 SUSPENDED 인 카드를 다시 정지해도 예외 없이 무시된다 — 이벤트 재수신 멱등")
    void suspendIsIdempotent() {
        Card card = Card.issue(1L, 888L, "1234-****-****-5678", new BigDecimal("500000"));
        card.suspend();
        card.suspend();   // member_removed 재수신

        assertThat(card.getStatus()).isEqualTo(CardStatus.SUSPENDED);
    }

    @Test
    @DisplayName("카드번호는 마스킹된 값만 보관한다")
    void onlyMaskedNumberStored() {
        Card card = Card.issue(1L, 888L, "1234-****-****-5678", new BigDecimal("500000"));
        assertThat(card.getMaskedCardNo()).doesNotContain("5678901234");
    }
```

> `suspend()` 멱등은 Task 12 의 `member_removed` 재수신 때문에 필요하다. `cancel()` 은 멱등이 아니다 — 해지는 명시적 운영 행위라 중복 요청을 삼키면 안 된다.

- [ ] **Step 7: 전체 도메인 테스트 통과 확인**

Run: `./gradlew :card-service:test --tests '*.card.domain.*'`
Expected: PASS

- [ ] **Step 8: 커밋**

```bash
git add card-service/src/main/java/github/lms/lemuel/card/domain card-service/src/test/java/github/lms/lemuel/card/domain
git commit -m "feat(card): 카드계정·카드 애그리거트와 상태머신·한도 불변식"
```

---

## Task 5: 한도 정책 `CardLimitPolicy`

**Files:**

- Create: `card-service/src/main/java/github/lms/lemuel/card/domain/CardLimitPolicy.java`
- Create: `card-service/src/main/java/github/lms/lemuel/card/domain/ScreeningResult.java`
- Test: `card-service/src/test/java/github/lms/lemuel/card/domain/CardLimitPolicyTest.java`

**Interfaces:**

- Consumes: Task 4 의 `LimitSnapshot`, `ReputationGrade`
- Produces:
  - `CardLimitPolicy(BigDecimal recognitionRatio, BigDecimal minimumLimit)` — 생성자 주입(설정값)
  - `screen(BigDecimal sellerPayable, BigDecimal holdbackPayable, ReputationGrade grade) : ScreeningResult`
  - `record ScreeningResult(boolean approved, BigDecimal masterLimit, LimitSnapshot snapshot, String rejectReason)`

- [ ] **Step 1: 실패 테스트 작성 — 경계값 포함**

```java
package github.lms.lemuel.card.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 한도 산식 고정: masterLimit = floor(F x R x H), F = sellerPayable + holdbackPayable.
 * 돈 경로라 대표값·라운딩 경계·0원·최소한도 경계를 모두 고정한다(money-safety 템플릿).
 */
class CardLimitPolicyTest {

    private final CardLimitPolicy policy =
            new CardLimitPolicy(new BigDecimal("0.70"), new BigDecimal("300000"));

    @Test
    @DisplayName("대표값 — F=1,000,000, R=0.70, H=1.00(B) → 700,000")
    void representativeCase() {
        ScreeningResult r = policy.screen(
                new BigDecimal("800000"), new BigDecimal("200000"), ReputationGrade.B);

        assertThat(r.approved()).isTrue();
        assertThat(r.masterLimit()).isEqualByComparingTo("700000");
        assertThat(r.snapshot().funding()).isEqualByComparingTo("1000000");
    }

    @Test
    @DisplayName("C등급 haircut 0.85 적용 — F=1,000,000 → 595,000")
    void gradeCHaircut() {
        ScreeningResult r = policy.screen(
                new BigDecimal("1000000"), BigDecimal.ZERO, ReputationGrade.C);
        assertThat(r.masterLimit()).isEqualByComparingTo("595000");
    }

    @Test
    @DisplayName("floor — 소수는 버린다. 원 단위 아래로 한도를 주지 않는다")
    void floorsToWon() {
        // 1,000,001 x 0.70 = 700,000.7 → 700,000
        ScreeningResult r = policy.screen(
                new BigDecimal("1000001"), BigDecimal.ZERO, ReputationGrade.A);
        assertThat(r.masterLimit()).isEqualByComparingTo("700000");
    }

    @Test
    @DisplayName("E등급은 haircut 0 → 산식이 0 을 내고 탈락한다")
    void gradeERejected() {
        ScreeningResult r = policy.screen(
                new BigDecimal("100000000"), BigDecimal.ZERO, ReputationGrade.E);

        assertThat(r.approved()).isFalse();
        assertThat(r.masterLimit()).isEqualByComparingTo("0");
        assertThat(r.rejectReason()).contains("평판");
    }

    @Test
    @DisplayName("최소한도 미달은 탈락 — 경계 바로 아래")
    void belowMinimumRejected() {
        // F=428,570 x 0.7 = 299,999 → 300,000 미만
        ScreeningResult r = policy.screen(
                new BigDecimal("428570"), BigDecimal.ZERO, ReputationGrade.A);

        assertThat(r.approved()).isFalse();
        assertThat(r.rejectReason()).contains("최소");
    }

    @Test
    @DisplayName("최소한도와 정확히 같으면 승인 — 경계값")
    void exactMinimumApproved() {
        // F=428,572 x 0.7 = 300,000.4 → floor 300,000
        ScreeningResult r = policy.screen(
                new BigDecimal("428572"), BigDecimal.ZERO, ReputationGrade.A);

        assertThat(r.approved()).isTrue();
        assertThat(r.masterLimit()).isEqualByComparingTo("300000");
    }

    @Test
    @DisplayName("재원 0원은 탈락")
    void zeroFundingRejected() {
        ScreeningResult r = policy.screen(BigDecimal.ZERO, BigDecimal.ZERO, ReputationGrade.A);
        assertThat(r.approved()).isFalse();
    }

    @Test
    @DisplayName("홀드백만 있어도 재원으로 인정된다 — 유보금도 셀러 몫이다")
    void holdbackOnlyCountsAsFunding() {
        ScreeningResult r = policy.screen(BigDecimal.ZERO, new BigDecimal("1000000"), ReputationGrade.A);
        assertThat(r.approved()).isTrue();
        assertThat(r.masterLimit()).isEqualByComparingTo("700000");
    }

    @Test
    @DisplayName("승인·탈락 어느 쪽이든 근거 스냅샷은 남는다")
    void snapshotAlwaysPreserved() {
        ScreeningResult rejected = policy.screen(BigDecimal.ZERO, BigDecimal.ZERO, ReputationGrade.E);
        assertThat(rejected.snapshot()).isNotNull();
        assertThat(rejected.snapshot().reputationGrade()).isEqualTo(ReputationGrade.E);
        assertThat(rejected.snapshot().appliedRatio()).isEqualByComparingTo("0.70");
    }

    @Test
    @DisplayName("음수 재원은 0 으로 본다 — 회계상 음수 잔액이 한도를 만들지 않는다")
    void negativeFundingTreatedAsZero() {
        ScreeningResult r = policy.screen(
                new BigDecimal("-500000"), BigDecimal.ZERO, ReputationGrade.A);
        assertThat(r.approved()).isFalse();
        assertThat(r.masterLimit()).isEqualByComparingTo("0");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :card-service:test --tests '*CardLimitPolicyTest'`
Expected: 컴파일 실패.

- [ ] **Step 3: 구현**

```java
package github.lms.lemuel.card.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 법인카드 한도 산정 정책 — 부수효과 없는 순수 도메인 정책.
 *
 * <pre>
 *   F = sellerPayable + holdbackPayable   (확정·미지급 정산금 + 홀드백 유보분)
 *   R = 인정비율 (설정 주입, 기본 0.70)
 *   H = 평판 haircut (A·B 1.00 / C 0.85 / D 0.70 / E 0.00)
 *   masterLimit = floor(F x R x H)
 * </pre>
 *
 * <p>R 이 1 이 아닌 이유: F 는 곧 셀러에게 지급될 돈이라, 카드 이용과 정산 지급이 같은 재원을
 * 두 번 쓸 수 있다. 실제 상계는 3단계(청구 사이클)의 몫이고, 그때까지 R 이 그 위험을 흡수한다.
 * 상계가 구현되면 이 값은 재조정 대상이다.
 */
public class CardLimitPolicy {

    private static final String FORMULA = "floor((sellerPayable + holdbackPayable) x R x H)";

    private final BigDecimal recognitionRatio;
    private final BigDecimal minimumLimit;

    public CardLimitPolicy(BigDecimal recognitionRatio, BigDecimal minimumLimit) {
        if (recognitionRatio == null || recognitionRatio.signum() <= 0
                || recognitionRatio.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("인정비율은 0 초과 1 이하여야 합니다: " + recognitionRatio);
        }
        if (minimumLimit == null || minimumLimit.signum() < 0) {
            throw new IllegalArgumentException("최소한도는 0 이상이어야 합니다: " + minimumLimit);
        }
        this.recognitionRatio = recognitionRatio;
        this.minimumLimit = minimumLimit;
    }

    public ScreeningResult screen(BigDecimal sellerPayable, BigDecimal holdbackPayable,
                                  ReputationGrade grade) {
        BigDecimal payable = nonNegative(sellerPayable);
        BigDecimal holdback = nonNegative(holdbackPayable);
        LimitSnapshot snapshot =
                new LimitSnapshot(payable, holdback, recognitionRatio, grade, FORMULA);

        // 원 단위 절사(FLOOR) — 반올림으로 1원이라도 더 주지 않는다.
        BigDecimal limit = snapshot.funding()
                .multiply(recognitionRatio)
                .multiply(grade.haircut())
                .setScale(0, RoundingMode.FLOOR);

        if (grade == ReputationGrade.E) {
            return ScreeningResult.rejected(snapshot, "평판 등급 E 는 카드 발급 대상이 아닙니다.");
        }
        if (limit.compareTo(minimumLimit) < 0) {
            return ScreeningResult.rejected(snapshot,
                    "산정 한도 " + limit.toPlainString() + " 원이 최소한도 "
                            + minimumLimit.toPlainString() + " 원에 미달합니다.");
        }
        return ScreeningResult.approved(limit, snapshot);
    }

    /** 회계상 음수 잔액(과지급 등)이 한도를 만들지 않도록 0 으로 바닥을 친다. */
    private static BigDecimal nonNegative(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return value;
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :card-service:test --tests '*CardLimitPolicyTest'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add card-service/src/main/java/github/lms/lemuel/card/domain card-service/src/test
git commit -m "feat(card): 재원·평판 기반 한도 산정 정책"
```

---

## Task 6: 영속 계층 (Flyway V4 + JPA 어댑터)

**Files:**

- Create: `card-service/src/main/resources/db/migration/V4__card_core.sql`
- Create: `card-service/src/main/java/github/lms/lemuel/card/adapter/out/persistence/CardAccountJpaEntity.java`
- Create: `.../persistence/CardJpaEntity.java`
- Create: `.../persistence/SpringDataCardAccountRepository.java`
- Create: `.../persistence/SpringDataCardRepository.java`
- Create: `.../persistence/CardAccountPersistenceAdapter.java`
- Create: `.../persistence/CardPersistenceAdapter.java`
- Create: `card-service/src/main/java/github/lms/lemuel/card/application/port/out/LoadCardAccountPort.java`
- Create: `.../port/out/SaveCardAccountPort.java` · `LoadCardPort.java` · `SaveCardPort.java`
- Test: `card-service/src/test/java/github/lms/lemuel/card/integration/CardPersistenceIT.java`

**Interfaces:**

- Consumes: Task 4 도메인
- Produces:
  - `LoadCardAccountPort.findByOrganizationId(Long) : Optional<CardAccount>` · `findById(Long)` · `findByIdForUpdate(Long) : Optional<CardAccount>` (**비관적 락**) · `findAllActive() : List<CardAccount>`
  - `SaveCardAccountPort.save(CardAccount) : CardAccount`
  - `LoadCardPort.findById(Long)` · `findByCardAccountId(Long) : List<Card>` · `findActiveByHolder(Long cardAccountId, Long holderUserId) : Optional<Card>` · `sumActiveSubLimits(Long cardAccountId) : BigDecimal`
  - `SaveCardPort.save(Card) : Card`

> **매핑 규약(organization-service 와 동일):** `created_at`/`updated_at` 은 DB `DEFAULT NOW()` 에 위임하고 엔티티에서 `insertable = false` 로 둔다. 어댑터가 도메인 스냅샷으로 detached 엔티티를 재구성해 merge 하므로, 이 조합이 아니면 감사 컬럼이 null 로 덮인다. `@Version` 낙관 락 컬럼도 함께 둔다.

- [ ] **Step 1: `V4__card_core.sql` 작성** (V1 이 아니라 V4 — Task 0 절 참조: 영속 볼륨이 있는 기존 DB 를 깨지 않기 위함)

```sql
-- V4: card-service 자체 DB(lemuel_card) — 카드계정·카드 코어
--
-- 셀러 법인의 카드계정(마스터 한도)과 임직원 카드(서브한도)를 둔다.
-- 조직·임직원은 organization-service 소유라 여기서는 비검증 비즈니스 키(organization_id, holder_user_id)로만 참조한다.
-- 핵심 불변식 master_limit >= SUM(sub_limit) 는 DB 제약으로 표현할 수 없어(집계 제약 부재)
-- 애플리케이션이 card_accounts 행 비관적 락 + 합계 재계산으로 강제한다 — CardIssuanceLimitConcurrencyIT 가 이를 증명한다.

CREATE TABLE card_accounts (
    id                   BIGSERIAL      PRIMARY KEY,
    organization_id      BIGINT         NOT NULL,
    seller_id            VARCHAR(64)    NOT NULL,
    status               VARCHAR(20)    NOT NULL DEFAULT 'SCREENING',
    master_limit         NUMERIC(19,2)  NOT NULL DEFAULT 0,

    -- 한도 산정 근거 스냅샷 — 사후에 "왜 이 한도였나"를 재현하기 위해 보존한다.
    screened_at          TIMESTAMPTZ,
    seller_payable_snap  NUMERIC(19,2),
    holdback_payable_snap NUMERIC(19,2),
    applied_ratio        NUMERIC(5,4),
    reputation_grade     VARCHAR(2),
    limit_formula        VARCHAR(200),
    reject_reason        VARCHAR(300),

    created_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    version              BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT chk_card_account_status
        CHECK (status IN ('SCREENING', 'ACTIVE', 'SUSPENDED', 'CLOSED', 'REJECTED')),
    CONSTRAINT chk_card_account_master_limit_non_negative CHECK (master_limit >= 0),
    CONSTRAINT chk_card_account_grade
        CHECK (reputation_grade IS NULL OR reputation_grade IN ('A', 'B', 'C', 'D', 'E'))
);

-- 조직당 카드계정 1개.
CREATE UNIQUE INDEX uq_card_account_org ON card_accounts (organization_id);
-- 재원 재조회(일 1회 재산정)에서 셀러 기준 조회.
CREATE INDEX idx_card_account_seller ON card_accounts (seller_id);
-- 재산정 스케줄러가 ACTIVE 만 훑는다.
CREATE INDEX idx_card_account_status ON card_accounts (status);

CREATE TABLE cards (
    id               BIGSERIAL      PRIMARY KEY,
    card_account_id  BIGINT         NOT NULL REFERENCES card_accounts(id),
    holder_user_id   BIGINT         NOT NULL,
    masked_card_no   VARCHAR(32)    NOT NULL,
    sub_limit        NUMERIC(19,2)  NOT NULL,
    status           VARCHAR(20)    NOT NULL DEFAULT 'ISSUED',
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    version          BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT chk_card_status CHECK (status IN ('ISSUED', 'SUSPENDED', 'CANCELED')),
    CONSTRAINT chk_card_sub_limit_non_negative CHECK (sub_limit >= 0)
);

-- ★ 임직원당 활성 카드 1장. CANCELED 는 슬롯을 비우므로 재발급이 가능하다.
--   동시 발급 경쟁의 최종 차단선(선검증 409 + 이 인덱스 이중 방어).
CREATE UNIQUE INDEX uq_card_active_holder
    ON cards (card_account_id, holder_user_id)
    WHERE status <> 'CANCELED';

-- 서브한도 합계 계산(발급·한도변경 핫패스).
CREATE INDEX idx_card_account_status_lookup ON cards (card_account_id, status);
-- 본인 카드 조회(GET /cards/me).
CREATE INDEX idx_card_holder ON cards (holder_user_id, status);

-- ── 조직·멤버·평판 프로젝션 (Task 7 이 채운다) ──
-- organization-service / company-service 소유 데이터의 읽기 전용 복제본.
-- 여기서 판정하는 것은 "이 요청자가 이 조직의 어떤 역할인가" 뿐이다.

CREATE TABLE org_projection (
    organization_id  BIGINT       PRIMARY KEY,
    name             VARCHAR(200) NOT NULL,
    type             VARCHAR(20)  NOT NULL,
    external_ref     VARCHAR(64),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_org_projection_type CHECK (type IN ('SELLER', 'CORPORATE'))
);

CREATE TABLE org_member_projection (
    organization_id  BIGINT       NOT NULL,
    user_id          BIGINT       NOT NULL,
    role             VARCHAR(20)  NOT NULL,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    PRIMARY KEY (organization_id, user_id),
    CONSTRAINT chk_org_member_role CHECK (role IN ('OWNER', 'MANAGER', 'STAFF'))
);

CREATE TABLE reputation_projection (
    seller_id   VARCHAR(64)  PRIMARY KEY,
    grade       VARCHAR(2)   NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_reputation_grade CHECK (grade IN ('A', 'B', 'C', 'D', 'E'))
);

COMMENT ON TABLE card_accounts IS '법인 카드계정. master_limit >= SUM(cards.sub_limit) 는 앱이 비관적 락으로 강제.';
COMMENT ON TABLE cards IS '임직원 카드. 활성 카드는 임직원당 1장(uq_card_active_holder).';
COMMENT ON TABLE org_member_projection IS 'organization-service 이벤트 프로젝션. 권한 판정 전용 — 여기가 낡으면 퇴사자 카드가 살아남는다.';
```

- [ ] **Step 2: 영속 IT 작성 — 실패 먼저**

`CardPersistenceIT` 에서 다음 4가지를 고정한다:

1. 카드계정 저장·조회 왕복 시 한도 스냅샷이 보존된다
2. 같은 조직에 카드계정 2개 생성 시 `uq_card_account_org` 위반
3. 같은 임직원에게 활성 카드 2장 발급 시 `uq_card_active_holder` 위반
4. **CANCELED 후에는 재발급이 가능하다** (partial unique 의 WHERE 절이 의도대로 동작)

```java
    @Test
    @DisplayName("해지된 카드는 슬롯을 비운다 — 같은 임직원에게 재발급 가능")
    void canceledCardFreesTheSlot() {
        CardAccount account = saveActiveAccount(3001L, "777", new BigDecimal("1000000"));

        Card first = saveCardPort.save(Card.issue(account.getId(), 888L, "1111-****-****-1111",
                new BigDecimal("100000")));
        first.cancel();
        saveCardPort.save(first);

        Card second = saveCardPort.save(Card.issue(account.getId(), 888L, "2222-****-****-2222",
                new BigDecimal("100000")));

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(loadCardPort.findActiveByHolder(account.getId(), 888L))
                .map(Card::getId).contains(second.getId());
    }

    @Test
    @DisplayName("서브한도 합계는 활성 카드만 센다 — 해지 카드가 한도를 계속 잡아먹으면 안 된다")
    void sumCountsActiveCardsOnly() {
        CardAccount account = saveActiveAccount(3002L, "778", new BigDecimal("1000000"));
        Card a = saveCardPort.save(Card.issue(account.getId(), 1L, "m1", new BigDecimal("300000")));
        saveCardPort.save(Card.issue(account.getId(), 2L, "m2", new BigDecimal("200000")));
        a.cancel();
        saveCardPort.save(a);

        assertThat(loadCardPort.sumActiveSubLimits(account.getId())).isEqualByComparingTo("200000");
    }
```

> `sumActiveSubLimits` 는 `status <> 'CANCELED'` 기준이다. **SUSPENDED 카드는 합계에 포함한다** — 정지는 일시적이고 재개되면 그 한도를 다시 써야 하므로, 합계에서 빼면 그 사이 다른 카드에 배분된 한도와 충돌한다.

- [ ] **Step 3: 실패 확인 → 엔티티·리포지토리·어댑터 구현**

`findByIdForUpdate` 는 비관적 락:

```java
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CardAccountJpaEntity a where a.id = :id")
    Optional<CardAccountJpaEntity> findByIdForUpdate(@Param("id") Long id);
```

```java
    @Query("""
            select coalesce(sum(c.subLimit), 0)
              from CardJpaEntity c
             where c.cardAccountId = :cardAccountId
               and c.status <> 'CANCELED'
            """)
    BigDecimal sumActiveSubLimits(@Param("cardAccountId") Long cardAccountId);
```

- [ ] **Step 4: 통과 확인 → 커밋**

Run: `./gradlew :card-service:test --tests '*CardPersistenceIT'`

```bash
git commit -am "feat(card): 카드계정·카드 영속 계층과 활성 슬롯 유니크 제약"
```

---

## Task 7: 이벤트 소비 프로젝션 (조직·멤버·평판)

**Files:**

- Create: `card-service/src/main/java/github/lms/lemuel/card/application/port/out/{LoadOrgProjectionPort,SaveOrgProjectionPort,LoadReputationPort,SaveReputationPort}.java`
- Create: `.../application/port/in/IngestOrgProjectionUseCase.java` · `IngestReputationUseCase.java`
- Create: `.../application/service/OrgProjectionService.java` · `ReputationProjectionService.java`
- Create: `.../adapter/in/kafka/OrganizationCreatedConsumer.java` · `OrganizationMemberJoinedConsumer.java` · `OrganizationMemberRoleChangedConsumer.java` · `OrganizationMemberRemovedConsumer.java` · `CompanyReputationChangedConsumer.java`
- Create: `.../adapter/out/persistence/{OrgProjectionJpaEntity,OrgMemberProjectionJpaEntity,ReputationProjectionJpaEntity}.java` + 리포지토리·어댑터
- Test: `card-service/src/test/java/github/lms/lemuel/card/adapter/in/kafka/EventContractConsumerTest.java`

**Interfaces:**

- Consumes: Task 3 의 신규 토픽 2종, Task 6 의 프로젝션 테이블
- Produces:
  - `LoadOrgProjectionPort.findOrg(Long orgId) : Optional<OrgView>` — `record OrgView(Long organizationId, String type, String externalRef)`
  - `LoadOrgProjectionPort.findMemberRole(Long orgId, Long userId) : Optional<OrgRole>` (활성 멤버만)
  - `LoadReputationPort.gradeOf(String sellerId) : ReputationGrade` (없으면 `ReputationGrade.unknownDefault()`)
  - 컨슈머 그룹은 전부 `"lemuel-card"`

- [x] **Step 1: 컨슈머 계약 테스트 작성 — 정본 샘플이 그대로 흘러야 한다**

loan-service `EventContractConsumerTest` 패턴을 그대로 쓴다.

```java
package github.lms.lemuel.card.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.card.application.port.in.IngestOrgProjectionUseCase;
import github.lms.lemuel.card.application.port.in.IngestOrgProjectionUseCase.MemberCommand;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventContractConsumerTest {

    @Mock IngestOrgProjectionUseCase ingestOrgProjectionUseCase;
    @Mock ProcessedEventRepository processedEventRepository;

    final ObjectMapper objectMapper = new ObjectMapper();

    private static ConsumerRecord<String, String> recordOf(String topic, String json) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, 0, 0L, null, json);
        record.headers().add("event_id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    @Test
    @DisplayName("member_removed 정본 샘플 → 카드 정지 커맨드로 전달된다")
    void memberRemovedSample_flowsIntoSuspendCommand() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        OrganizationMemberRemovedConsumer consumer = new OrganizationMemberRemovedConsumer(
                ingestOrgProjectionUseCase, processedEventRepository, objectMapper);

        String sample = EventContractValidator.canonicalSample("lemuel.organization.member_removed");
        consumer.onMemberRemoved(
                recordOf("lemuel.organization.member_removed", sample), mock(Acknowledgment.class));

        verify(ingestOrgProjectionUseCase).removeMember(3001L, 888L);
    }

    @Test
    @DisplayName("member_role_changed 정본 샘플 → 역할 갱신 커맨드로 전달된다")
    void memberRoleChangedSample_flowsIntoUpdateCommand() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        OrganizationMemberRoleChangedConsumer consumer = new OrganizationMemberRoleChangedConsumer(
                ingestOrgProjectionUseCase, processedEventRepository, objectMapper);

        String sample = EventContractValidator.canonicalSample("lemuel.organization.member_role_changed");
        consumer.onMemberRoleChanged(
                recordOf("lemuel.organization.member_role_changed", sample), mock(Acknowledgment.class));

        verify(ingestOrgProjectionUseCase).upsertMember(new MemberCommand(3001L, 888L, "MANAGER"));
    }

    @Test
    @DisplayName("이미 처리한 event_id 는 재수신해도 커맨드를 호출하지 않는다 — 멱등")
    void duplicateEventIsSkipped() {
        when(processedEventRepository.existsById(any())).thenReturn(true);
        OrganizationMemberRemovedConsumer consumer = new OrganizationMemberRemovedConsumer(
                ingestOrgProjectionUseCase, processedEventRepository, objectMapper);

        String sample = EventContractValidator.canonicalSample("lemuel.organization.member_removed");
        consumer.onMemberRemoved(
                recordOf("lemuel.organization.member_removed", sample), mock(Acknowledgment.class));

        verify(ingestOrgProjectionUseCase, never()).removeMember(any(Long.class), any(Long.class));
    }

    @Test
    @DisplayName("필수 필드 누락 payload → IllegalArgumentException(즉시 DLT) + 커맨드 미호출")
    void missingRequiredFieldThrows() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        OrganizationMemberRemovedConsumer consumer = new OrganizationMemberRemovedConsumer(
                ingestOrgProjectionUseCase, processedEventRepository, objectMapper);

        assertThatThrownBy(() -> consumer.onMemberRemoved(
                recordOf("lemuel.organization.member_removed", "{\"organizationId\":3001}"),
                mock(Acknowledgment.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");

        verify(ingestOrgProjectionUseCase, never()).removeMember(any(Long.class), any(Long.class));
    }
}
```

- [x] **Step 2: 실패 확인 → 컨슈머 5종 구현**

전부 `IdempotentEventConsumer` 를 상속하고 `@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")` 를 붙인다. 예시:

```java
package github.lms.lemuel.card.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.card.application.port.in.IngestOrgProjectionUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 조직 이탈 수신 → 멤버 프로젝션 비활성화 + 해당 임직원 카드 자동 정지(Task 12).
 *
 * <p>이 컨슈머가 없으면 조직에서 제거된 임직원의 카드가 유효한 채로 남는다.
 * 멱등: 이미 정지된 카드를 다시 정지해도 무해하다(Card#suspend 는 멱등).
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class OrganizationMemberRemovedConsumer extends IdempotentEventConsumer {

    private static final String CONSUMER_GROUP = "lemuel-card";

    private final IngestOrgProjectionUseCase useCase;

    public OrganizationMemberRemovedConsumer(IngestOrgProjectionUseCase useCase,
                                             ProcessedEventRepository processedEventRepository,
                                             ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.useCase = useCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.organization-member-removed}",
            groupId = CONSUMER_GROUP, containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onMemberRemoved(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return CONSUMER_GROUP;
    }

    @Override
    protected String eventType() {
        return "OrganizationMemberRemoved";
    }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        long organizationId = requiredLong(node, "organizationId", eventId);
        long userId = requiredLong(node, "userId", eventId);
        useCase.removeMember(organizationId, userId);
        log.info("조직 이탈 반영 — 카드 정지 대상. eventId={}, orgId={}, userId={}",
                eventId, organizationId, userId);
    }
}
```

`OrganizationCreatedConsumer` 는 **`type != SELLER` 면 무시**한다 (CORPORATE 조직은 1단계 대상이 아니다). `CompanyReputationChangedConsumer` 는 평판 등급을 `reputation_projection` 에 upsert 한다.

- [x] **Step 3: 프로젝션 서비스·어댑터 구현 → 통과 확인**

Run: `./gradlew :card-service:test --tests '*EventContractConsumerTest'`
Expected: PASS

- [x] **Step 4: 커밋**

```bash
git commit -am "feat(card): 조직·멤버·평판 이벤트 프로젝션 컨슈머 5종"
```

---

## Task 8: 재원 조회 어댑터 (account-service 내부 API)

**Files:**

- Create: `card-service/src/main/java/github/lms/lemuel/card/application/port/out/LoadSellerFundingPort.java`
- Create: `card-service/src/main/java/github/lms/lemuel/card/adapter/out/external/AccountFundingAdapter.java`
- Create: `card-service/src/main/java/github/lms/lemuel/card/adapter/out/external/FundingUnavailableException.java`
- Test: `card-service/src/test/java/github/lms/lemuel/card/adapter/out/external/AccountFundingAdapterTest.java`

**Interfaces:**

- Consumes: Task 2 의 `GET /internal/account/sellers/{sellerId}/funding`
- Produces:
  - `LoadSellerFundingPort.load(String sellerId) : SellerFunding` — `record SellerFunding(BigDecimal sellerPayable, BigDecimal holdbackPayable)`. **Task 2 의 account-service `SellerFundingQuery.SellerFunding` 과 이름은 같지만 다른 타입이다** — 그쪽은 `sellerId` 필드를 포함하고 패키지도 다르다. card-service 는 자기 포트 타입만 쓴다(서비스 간 타입 공유 금지).
  - `FundingUnavailableException(String message, Throwable cause) extends RuntimeException` — 실패 시 던진다. Task 9 의 `CardExceptionHandler` 가 `ErrorCode.CARD_FUNDING_UNAVAILABLE` → 503 으로 번역한다.
  - 생성자 3개: `AccountFundingAdapter(String baseUrl, String internalApiKey)` (`@Autowired`, 프로덕션) · `AccountFundingAdapter(RestClient client, Duration retryBackoff)` (패키지-프라이빗, 테스트) · `AccountFundingAdapter(String baseUrl, String internalApiKey, RestClient.Builder builder)` (패키지-프라이빗, **헤더 검증 테스트 전용** — 빌더에 헤더를 얹는 경로를 그대로 태운다)

> **전례를 따른다:** `/internal/**` 호출에 Resilience4j 를 쓰는 코드는 리포에 없다. settlement 의 `OrderReconClient` 처럼 **수제 재시도**(총 2회, 200ms 백오프, 4xx 즉시 실패, 5xx·`ResourceAccessException` 재시도)를 복제하고, 최종 실패를 전용 예외로 번역한다.
>
> **폴백을 두지 않는다.** loan 은 기준금리·담보평가 실패 시 제시값으로 폴백해 신청 가용성을 우선하지만, 재원은 다르다. 재원을 모르는 상태에서 추정 한도를 부여하면 그 자체가 여신 사고다.
>
> **전례 대비 개선 1건:** `OrderReconClientTest` 는 `X-Internal-Api-Key` 헤더가 실제로 실리는지 검증하지 않는다(테스트가 패키지-프라이빗 생성자로 우회). 여기서는 헤더 검증 테스트를 반드시 추가한다.

- [x] **Step 1: 실패 테스트 작성**

```java
package github.lms.lemuel.card.adapter.out.external;

import github.lms.lemuel.card.application.port.out.LoadSellerFundingPort.SellerFunding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * account 내부 재원 API 와의 계약 검증. 재시도는 백오프 0 으로 지연 없이 확인한다.
 */
class AccountFundingAdapterTest {

    private static final String URI = "http://account-test/internal/account/sellers/777/funding";

    private MockRestServiceServer server;
    private RestClient.Builder builder;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl("http://account-test");
        server = MockRestServiceServer.bindTo(builder).build();
    }

    private AccountFundingAdapter adapter() {
        return new AccountFundingAdapter(builder.build(), Duration.ZERO);
    }

    @Test
    @DisplayName("문자열 금액을 BigDecimal 로 정확히 파싱한다")
    void parsesStringAmounts() {
        server.expect(requestTo(URI)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"sellerId\":\"777\",\"sellerPayable\":\"170000.00\",\"holdbackPayable\":\"10000.00\"}",
                        APPLICATION_JSON));

        SellerFunding funding = adapter().load("777");

        assertThat(funding.sellerPayable()).isEqualByComparingTo("170000.00");
        assertThat(funding.holdbackPayable()).isEqualByComparingTo("10000.00");
        server.verify();
    }

    @Test
    @DisplayName("X-Internal-Api-Key 헤더를 실어 보낸다 — 운영 account 는 fail-closed 다")
    void sendsInternalApiKeyHeader() {
        RestClient.Builder keyed = RestClient.builder().baseUrl("http://account-test");
        MockRestServiceServer keyedServer = MockRestServiceServer.bindTo(keyed).build();
        keyedServer.expect(requestTo(URI))
                .andExpect(header("X-Internal-Api-Key", "test-secret"))
                .andRespond(withSuccess(
                        "{\"sellerId\":\"777\",\"sellerPayable\":\"1\",\"holdbackPayable\":\"0\"}",
                        APPLICATION_JSON));

        new AccountFundingAdapter("http://account-test", "test-secret", keyed).load("777");

        keyedServer.verify();
    }

    @Test
    @DisplayName("5xx 는 재시도하고, 두 번째가 성공하면 값을 돌려준다")
    void retriesOnServerErrorThenSucceeds() {
        server.expect(requestTo(URI)).andRespond(withServerError());
        server.expect(requestTo(URI)).andRespond(withSuccess(
                "{\"sellerId\":\"777\",\"sellerPayable\":\"5\",\"holdbackPayable\":\"5\"}",
                APPLICATION_JSON));

        assertThat(adapter().load("777").sellerPayable()).isEqualByComparingTo("5");
        server.verify();
    }

    @Test
    @DisplayName("두 시도 모두 5xx 면 FundingUnavailableException — 폴백 없음")
    void exhaustedRetriesThrow() {
        server.expect(requestTo(URI)).andRespond(withServerError());
        server.expect(requestTo(URI)).andRespond(withServerError());

        assertThatThrownBy(() -> adapter().load("777"))
                .isInstanceOf(FundingUnavailableException.class);
        server.verify();
    }

    @Test
    @DisplayName("연결 실패(타임아웃 계열)도 재시도 후 실패로 번역된다")
    void connectionFailureTranslated() {
        server.expect(requestTo(URI)).andRespond(r -> { throw new IOException("simulated timeout"); });
        server.expect(requestTo(URI)).andRespond(r -> { throw new IOException("simulated timeout"); });

        assertThatThrownBy(() -> adapter().load("777"))
                .isInstanceOf(FundingUnavailableException.class);
        server.verify();
    }

    @Test
    @DisplayName("401 은 재시도하지 않는다 — 키가 틀린 건 재시도로 낫지 않는다")
    void unauthorizedIsNotRetried() {
        server.expect(requestTo(URI)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> adapter().load("777"))
                .isInstanceOf(FundingUnavailableException.class);
        server.verify();   // 단 1회만 등록 — 재시도했다면 verify 가 실패한다
    }

    @Test
    @DisplayName("응답 바디가 비면 실패로 본다 — 재원 0 으로 오해하지 않는다")
    void emptyBodyIsFailureNotZero() {
        server.expect(requestTo(URI)).andRespond(withSuccess("", APPLICATION_JSON));

        assertThatThrownBy(() -> adapter().load("777"))
                .isInstanceOf(FundingUnavailableException.class);
    }
}
```

> 마지막 테스트가 중요하다. 응답을 못 읽었을 때 `null` 을 0 으로 정규화하면 **재원 0 = 심사 탈락**이 되어 장애가 "이 셀러는 자격 미달"로 둔갑한다. 실패는 실패여야 한다.

- [x] **Step 2: 실패 확인 → 어댑터 구현**

```java
    @Autowired
    public AccountFundingAdapter(
            @Value("${app.account-service.base-url:http://localhost:8102}") String baseUrl,
            @Value("${app.internal.api-key:}") String internalApiKey) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl).requestFactory(factory);
        if (internalApiKey != null && !internalApiKey.isBlank()) {
            builder.defaultHeader(InternalApiKeyFilter.HEADER, internalApiKey);
        }
        this.client = builder.build();
        this.retryBackoff = Duration.ofMillis(200);
    }
```

- [x] **Step 3: 통과 확인 → 커밋**

Run: `./gradlew :card-service:test --tests '*AccountFundingAdapterTest'`

```bash
git commit -am "feat(card): account 재원 조회 어댑터 — 재시도 후 명시적 실패, 폴백 없음"
```

---

## Task 9: 카드계정 개설(심사)

**Files:**

- Create: `card-service/src/main/java/github/lms/lemuel/card/application/port/in/OpenCardAccountUseCase.java`
- Create: `.../application/service/OpenCardAccountService.java`
- Create: `.../application/service/CardOrgAuthorizer.java`
- Create: `.../application/port/out/PublishCardEventPort.java`
- Create: `.../adapter/out/event/CardEventPublisherAdapter.java`
- Create: `.../adapter/in/web/CardController.java` + `dto/OpenCardAccountRequest.java` · `CardAccountResponse.java`
- Create: `.../adapter/in/web/CardExceptionHandler.java`
- Create: `shared-common/src/testFixtures/resources/contracts/events/lemuel.card.account_opened.schema.json` + 샘플
- Test: `.../application/service/OpenCardAccountServiceTest.java`
- Test: `.../adapter/out/event/CardEventContractTest.java`
- Test: `.../adapter/in/web/CardControllerTest.java`

**Interfaces:**

- Consumes: Task 5 `CardLimitPolicy`, Task 6 포트, Task 7 프로젝션, Task 8 `LoadSellerFundingPort`
- Produces:
  - `OpenCardAccountUseCase.open(OpenCardAccountCommand) : CardAccount` — `record OpenCardAccountCommand(Long organizationId, Long requesterUserId)`
  - `CardOrgAuthorizer.requireRole(Long orgId, Long userId, Set<OrgRole> allowed, String action)` — 위반 시 `BusinessException(CARD_FORBIDDEN)`
  - `PublishCardEventPort.publishAccountOpened(CardAccount)` · `publishIssued(Card, CardAccount)` · `publishLimitChanged(...)` · `publishStatusChanged(...)`
  - `POST /api/cards/accounts` → 201 + `CardAccountResponse`
  - 토픽 `lemuel.card.account_opened` — `{cardAccountId:int, organizationId:int, sellerId:str, masterLimit:str, reputationGrade:str}`

- [x] **Step 1: 서비스 실패 테스트 작성**

```java
    @Test
    @DisplayName("심사 통과 → ACTIVE 저장 + account_opened 발행")
    void openApprovedPublishesEvent() {
        when(loadOrgProjectionPort.findOrg(3001L))
                .thenReturn(Optional.of(new OrgView(3001L, "SELLER", "777")));
        when(loadCardAccountPort.findByOrganizationId(3001L)).thenReturn(Optional.empty());
        when(loadSellerFundingPort.load("777"))
                .thenReturn(new SellerFunding(new BigDecimal("800000"), new BigDecimal("200000")));
        when(loadReputationPort.gradeOf("777")).thenReturn(ReputationGrade.B);

        CardAccount account = service.open(new OpenCardAccountCommand(3001L, 100L));

        assertThat(account.getStatus()).isEqualTo(CardAccountStatus.ACTIVE);
        assertThat(account.getMasterLimit()).isEqualByComparingTo("700000");
        verify(publishCardEventPort).publishAccountOpened(any());
    }

    @Test
    @DisplayName("E등급은 422 로 거절되고 REJECTED 로 기록된다 — 근거를 남긴다")
    void openRejectedForGradeE() {
        stubOrgAndFunding(new BigDecimal("10000000"), ReputationGrade.E);

        assertThatThrownBy(() -> service.open(new OpenCardAccountCommand(3001L, 100L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CARD_SCREENING_REJECTED);

        ArgumentCaptor<CardAccount> saved = ArgumentCaptor.forClass(CardAccount.class);
        verify(saveCardAccountPort).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(CardAccountStatus.REJECTED);
        assertThat(saved.getValue().getLimitSnapshot()).isNotNull();
        verify(publishCardEventPort, never()).publishAccountOpened(any());
    }

    @Test
    @DisplayName("조직 타입이 SELLER 가 아니면 422 — 1단계는 셀러 전용")
    void nonSellerOrgRejected() {
        when(loadOrgProjectionPort.findOrg(3001L))
                .thenReturn(Optional.of(new OrgView(3001L, "CORPORATE", "005930")));

        assertThatThrownBy(() -> service.open(new OpenCardAccountCommand(3001L, 100L)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("externalRef 가 없으면 sellerId 를 해석할 수 없어 422")
    void missingExternalRefRejected() {
        when(loadOrgProjectionPort.findOrg(3001L))
                .thenReturn(Optional.of(new OrgView(3001L, "SELLER", null)));

        assertThatThrownBy(() -> service.open(new OpenCardAccountCommand(3001L, 100L)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("이미 카드계정이 있으면 409")
    void duplicateAccountRejected() {
        when(loadOrgProjectionPort.findOrg(3001L))
                .thenReturn(Optional.of(new OrgView(3001L, "SELLER", "777")));
        when(loadCardAccountPort.findByOrganizationId(3001L))
                .thenReturn(Optional.of(mock(CardAccount.class)));

        assertThatThrownBy(() -> service.open(new OpenCardAccountCommand(3001L, 100L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CARD_ACCOUNT_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("OWNER 가 아니면 403 — 재원 조회조차 하지 않는다")
    void nonOwnerForbiddenBeforeFundingCall() {
        doThrow(new BusinessException(ErrorCode.CARD_FORBIDDEN))
                .when(authorizer).requireRole(eq(3001L), eq(200L), any(), anyString());

        assertThatThrownBy(() -> service.open(new OpenCardAccountCommand(3001L, 200L)))
                .isInstanceOf(BusinessException.class);
        verify(loadSellerFundingPort, never()).load(anyString());
    }

    @Test
    @DisplayName("재원 조회 실패는 503 으로 번역되고 아무것도 저장하지 않는다")
    void fundingFailureIsTranslatedAndNothingSaved() {
        when(loadOrgProjectionPort.findOrg(3001L))
                .thenReturn(Optional.of(new OrgView(3001L, "SELLER", "777")));
        when(loadCardAccountPort.findByOrganizationId(3001L)).thenReturn(Optional.empty());
        when(loadSellerFundingPort.load("777")).thenThrow(new FundingUnavailableException("down", null));

        assertThatThrownBy(() -> service.open(new OpenCardAccountCommand(3001L, 100L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CARD_FUNDING_UNAVAILABLE);
        verify(saveCardAccountPort, never()).save(any());
    }
```

> 마지막 테스트의 의미: 재원을 모를 때는 **아무 상태도 남기지 않는다.** REJECTED 로 기록하면 "심사 탈락"이라는 사실이 아닌 기록이 남는다.

- [x] **Step 2: 실패 확인 → `CardOrgAuthorizer` 구현**

```java
/**
 * 조직 역할 기반 인가. organization-service 의 OrgAuthorizer 를 미러링한다.
 *
 * <p>★ 권한은 요청 파라미터가 아니라 JWT 주체(uid)가 그 조직의 멤버 프로젝션에서 갖는 역할로 판정한다.
 * 요청 본문의 역할을 믿으면 그 자체가 권한 상승 경로다(IDOR).
 */
@Component
public class CardOrgAuthorizer {

    private final LoadOrgProjectionPort loadOrgProjectionPort;

    public OrgRole requireRole(Long organizationId, Long userId, Set<OrgRole> allowed, String action) {
        OrgRole role = loadOrgProjectionPort.findMemberRole(organizationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CARD_FORBIDDEN,
                        "조직 " + organizationId + " 의 활성 구성원이 아닙니다."));
        if (!allowed.contains(role)) {
            throw new BusinessException(ErrorCode.CARD_FORBIDDEN,
                    action + " 권한이 없습니다. 필요=" + allowed + ", 현재=" + role);
        }
        return role;
    }
}
```

- [x] **Step 3: `OpenCardAccountService` 구현**

순서를 지킨다: **인가 → 조직 검증 → 중복 검증 → 재원 조회 → 평판 조회 → 산정 → 저장 → 발행.** 재원 조회는 외부 호출이라 인가·검증을 통과한 뒤에만 한다.

`@Transactional` 안에서 저장과 Outbox 기록이 함께 커밋되어야 한다.

- [x] **Step 4: 계약 스키마 + 프로듀서 계약 테스트**

`lemuel.card.account_opened.schema.json` — 금액은 문자열(`^[0-9]+(\\.[0-9]+)?$`).

```java
    @Test
    @DisplayName("account_opened 페이로드는 계약을 만족하고 금액이 문자열이다")
    void accountOpened_satisfiesContract() {
        publisher.publishAccountOpened(activeAccount());

        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        String payload = outboxCaptor.getValue().getPayload();
        EventContractValidator.assertValid("lemuel.card.account_opened", payload);
        assertThat(payload).contains("\"masterLimit\":\"700000\"");
    }
```

- [x] **Step 5: 컨트롤러 + 예외 핸들러**

`CardExceptionHandler` 는 `@Order(Ordered.HIGHEST_PRECEDENCE)` — shared-common 핸들러(LOWEST)보다 먼저 도메인 예외를 잡아야 한다.

```java
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "github.lms.lemuel.card")
public class CardExceptionHandler {

    @ExceptionHandler(SubLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleSubLimit(SubLimitExceededException e) {
        return toResponse(ErrorCode.CARD_SUB_LIMIT_EXCEEDED, e.getMessage());
    }

    @ExceptionHandler(InvalidCardTransitionException.class)
    public ResponseEntity<ErrorResponse> handleTransition(InvalidCardTransitionException e) {
        return toResponse(ErrorCode.INVALID_STATE, e.getMessage());
    }

    @ExceptionHandler(FundingUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleFunding(FundingUnavailableException e) {
        // 폴백 없음 — 재원을 모른 채 추정 한도를 주지 않는다.
        return toResponse(ErrorCode.CARD_FUNDING_UNAVAILABLE, null);
    }
}
```

컨트롤러 테스트는 `@WebMvcTest(controllers = CardController.class)` + `@AutoConfigureMockMvc(addFilters = false)` + `@MockitoBean JwtUtil` (loan-service `RepaymentControllerTest` 패턴). 역할별 403/422 를 검증한다.

- [x] **Step 6: 통과 확인 → 커밋**

Run: `./gradlew :card-service:test`

```bash
git commit -am "feat(card): 카드계정 개설·심사 유스케이스와 REST 표면"
```

---

## Task 10: 임직원 카드 발급 (비관적 락)

**Files:**

- Create: `.../application/port/in/IssueCardUseCase.java` · `.../application/service/IssueCardService.java`
- Create: `.../application/port/out/CardIssuerPort.java` · `.../adapter/out/external/MockCardIssuerAdapter.java`
- Modify: `.../adapter/in/web/CardController.java` (`POST /accounts/{id}/cards`)
- Create: `shared-common/.../contracts/events/lemuel.card.issued.schema.json` + 샘플
- Test: `.../application/service/IssueCardServiceTest.java`
- Test: `.../integration/CardIssuanceLimitConcurrencyIT.java` ← **이 설계의 핵심 검증**

**Interfaces:**

- Consumes: Task 6 `findByIdForUpdate`·`sumActiveSubLimits`, Task 7 멤버 프로젝션
- Produces:
  - `IssueCardUseCase.issue(IssueCardCommand) : Card` — `record IssueCardCommand(Long cardAccountId, Long holderUserId, BigDecimal subLimit, Long requesterUserId)`
  - `CardIssuerPort.issue(Long cardAccountId, Long holderUserId) : IssuedCard` — `record IssuedCard(String maskedCardNo)`

- [x] **Step 1: 서비스 실패 테스트**

```java
    @Test
    @DisplayName("발급은 카드계정을 비관적 락으로 잠근 뒤 합계를 재계산한다")
    void issueLocksAccountBeforeSumming() {
        stubActiveAccount(new BigDecimal("1000000"));
        when(loadCardPort.sumActiveSubLimits(1L)).thenReturn(new BigDecimal("900000"));
        when(loadOrgProjectionPort.findMemberRole(3001L, 888L)).thenReturn(Optional.of(OrgRole.STAFF));

        service.issue(new IssueCardCommand(1L, 888L, new BigDecimal("100000"), 100L));

        InOrder order = inOrder(loadCardAccountPort, loadCardPort, saveCardPort);
        order.verify(loadCardAccountPort).findByIdForUpdate(1L);
        order.verify(loadCardPort).sumActiveSubLimits(1L);
        order.verify(saveCardPort).save(any());
    }

    @Test
    @DisplayName("대상이 조직의 활성 멤버가 아니면 422 — 채번도 하지 않는다")
    void nonMemberRejectedBeforeIssuing() {
        stubActiveAccount(new BigDecimal("1000000"));
        when(loadOrgProjectionPort.findMemberRole(3001L, 999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issue(
                new IssueCardCommand(1L, 999L, new BigDecimal("10000"), 100L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CARD_HOLDER_NOT_MEMBER);
        verify(cardIssuerPort, never()).issue(any(), any());
    }

    @Test
    @DisplayName("이미 활성 카드가 있으면 409")
    void duplicateActiveCardRejected() {
        stubActiveAccount(new BigDecimal("1000000"));
        when(loadOrgProjectionPort.findMemberRole(3001L, 888L)).thenReturn(Optional.of(OrgRole.STAFF));
        when(loadCardPort.findActiveByHolder(1L, 888L)).thenReturn(Optional.of(mock(Card.class)));

        assertThatThrownBy(() -> service.issue(
                new IssueCardCommand(1L, 888L, new BigDecimal("10000"), 100L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CARD_ALREADY_ISSUED);
    }

    @Test
    @DisplayName("마스터 한도를 넘으면 422 이고 카드는 저장되지 않는다")
    void overLimitRejected() {
        stubActiveAccount(new BigDecimal("1000000"));
        when(loadCardPort.sumActiveSubLimits(1L)).thenReturn(new BigDecimal("950000"));
        when(loadOrgProjectionPort.findMemberRole(3001L, 888L)).thenReturn(Optional.of(OrgRole.STAFF));

        assertThatThrownBy(() -> service.issue(
                new IssueCardCommand(1L, 888L, new BigDecimal("100000"), 100L)))
                .isInstanceOf(SubLimitExceededException.class);
        verify(saveCardPort, never()).save(any());
    }

    @Test
    @DisplayName("OWNER 만 발급할 수 있다 — MANAGER 도 403")
    void onlyOwnerCanIssue() {
        doThrow(new BusinessException(ErrorCode.CARD_FORBIDDEN))
                .when(authorizer).requireRole(eq(3001L), eq(200L), eq(Set.of(OrgRole.OWNER)), anyString());

        assertThatThrownBy(() -> service.issue(
                new IssueCardCommand(1L, 888L, new BigDecimal("10000"), 200L)))
                .isInstanceOf(BusinessException.class);
    }
```

- [x] **Step 2: 실패 확인 → 서비스 구현**

```java
    @Override
    @Transactional
    public Card issue(IssueCardCommand command) {
        // 락은 검증보다 먼저 — 합계를 읽은 뒤 잠그면 그 사이에 다른 발급이 끼어든다.
        CardAccount account = loadCardAccountPort.findByIdForUpdate(command.cardAccountId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CARD_ACCOUNT_NOT_FOUND));

        authorizer.requireRole(account.getOrganizationId(), command.requesterUserId(),
                Set.of(OrgRole.OWNER), "카드 발급");

        loadOrgProjectionPort.findMemberRole(account.getOrganizationId(), command.holderUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CARD_HOLDER_NOT_MEMBER));

        loadCardPort.findActiveByHolder(account.getId(), command.holderUserId())
                .ifPresent(existing -> { throw new BusinessException(ErrorCode.CARD_ALREADY_ISSUED); });

        BigDecimal currentSum = loadCardPort.sumActiveSubLimits(account.getId());
        account.assertCanIssue(currentSum, command.subLimit());

        IssuedCard issued = cardIssuerPort.issue(account.getId(), command.holderUserId());
        Card card = saveCardPort.save(Card.issue(
                account.getId(), command.holderUserId(), issued.maskedCardNo(), command.subLimit()));

        publishCardEventPort.publishIssued(card, account);
        return card;
    }
```

- [x] **Step 3: 동시성 IT 작성 — 이 계획의 핵심**

```java
    @Test
    @DisplayName("동시 발급이 마스터 한도를 넘지 못한다 — 애그리거트를 쪼갠 대가를 락으로 갚는다")
    void concurrentIssuanceNeverExceedsMasterLimit() throws Exception {
        // 마스터 100만. 각 60만씩 4명에게 동시 발급 시도 → 최대 1건만 성공해야 한다.
        CardAccount account = saveActiveAccount(3001L, "777", new BigDecimal("1000000"));
        for (long uid = 1; uid <= 4; uid++) {
            saveMemberProjection(3001L, uid, OrgRole.STAFF);
        }
        saveMemberProjection(3001L, 100L, OrgRole.OWNER);

        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger succeeded = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (long uid = 1; uid <= threads; uid++) {
                final long holder = uid;
                pool.submit(() -> {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        issueCardUseCase.issue(new IssueCardCommand(
                                account.getId(), holder, new BigDecimal("600000"), 100L));
                        succeeded.incrementAndGet();
                    } catch (Exception ignored) {
                        // 경쟁에서 진 스레드: SubLimitExceededException 또는 락 대기 후 한도 초과
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(loadCardPort.sumActiveSubLimits(account.getId()))
                .isLessThanOrEqualTo(new BigDecimal("1000000"))
                .isEqualByComparingTo("600000");
    }

    @Test
    @DisplayName("한도에 딱 맞는 동시 발급 2건은 둘 다 성공한다 — 락이 과도하게 막지 않는다")
    void concurrentIssuanceWithinLimitBothSucceed() throws Exception {
        CardAccount account = saveActiveAccount(3002L, "778", new BigDecimal("1000000"));
        saveMemberProjection(3002L, 1L, OrgRole.STAFF);
        saveMemberProjection(3002L, 2L, OrgRole.STAFF);
        saveMemberProjection(3002L, 100L, OrgRole.OWNER);

        int threads = 2;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger succeeded = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (long uid = 1; uid <= threads; uid++) {
                final long holder = uid;
                pool.submit(() -> {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        issueCardUseCase.issue(new IssueCardCommand(
                                account.getId(), holder, new BigDecimal("500000"), 100L));
                        succeeded.incrementAndGet();
                    } catch (Exception ignored) {
                        // 둘 다 성공해야 하므로 여기 걸리면 아래 어서션이 실패한다.
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded.get()).isEqualTo(2);
        assertThat(loadCardPort.sumActiveSubLimits(account.getId())).isEqualByComparingTo("1000000");
    }
```

> 두 번째 테스트가 없으면 "전부 실패시키는 락"도 첫 테스트를 통과한다. 불변식을 지키는 것과 처리량을 죽이는 것은 다르다.

- [x] **Step 4: 통과 확인 → 커밋**

Run: `./gradlew :card-service:test --tests '*CardIssuanceLimitConcurrencyIT'`

```bash
git commit -am "feat(card): 임직원 카드 발급 — 비관적 락으로 한도 불변식 강제"
```

---

## Task 11: 서브한도·상태 변경

**Files:**

- Create: `.../application/port/in/ChangeSubLimitUseCase.java` · `ChangeCardStatusUseCase.java` + 서비스 2종
- Modify: `.../adapter/in/web/CardController.java` (`PATCH` 2종, `GET` 3종)
- Create: `shared-common/.../contracts/events/lemuel.card.limit_changed.schema.json` · `lemuel.card.status_changed.schema.json` + 샘플
- Test: 서비스 단위 테스트 2종 + 컨트롤러 테스트

**Interfaces:**

- Consumes: Task 10 의 락 패턴
- Produces:
  - `PATCH /api/cards/cards/{cardId}/limit` (OWNER) · `PATCH /api/cards/cards/{cardId}/status` (OWNER·MANAGER)
  - `GET /api/cards/accounts/{id}` · `GET /api/cards/accounts/{id}/cards` · `GET /api/cards/cards/me`
  - 토픽 `lemuel.card.limit_changed` — `{cardAccountId, cardId(nullable), previousLimit:str, newLimit:str, clamped:bool, scope:"MASTER"|"SUB"}`
  - 토픽 `lemuel.card.status_changed` — `{cardId, cardAccountId, previousStatus, newStatus, reason}`

- [x] **Step 1: 실패 테스트 — 서브한도 상향도 락이 필요하다**

```java
    @Test
    @DisplayName("서브한도 상향은 자기 몫을 뺀 합계와 비교한다 — 자기 자신을 두 번 세면 안 된다")
    void raiseComparesAgainstSumExcludingSelf() {
        // 마스터 100만, 기존 카드 A=60만(본인), B=30만 → 합계 90만
        // A 를 70만으로 올리면 70+30=100만 → 정확히 한도. 허용돼야 한다.
        stubActiveAccount(new BigDecimal("1000000"));
        stubCard(10L, 1L, new BigDecimal("600000"));
        when(loadCardPort.sumActiveSubLimits(1L)).thenReturn(new BigDecimal("900000"));

        service.change(new ChangeSubLimitCommand(10L, new BigDecimal("700000"), 100L));

        verify(saveCardPort).save(argThat(c -> c.getSubLimit().compareTo(new BigDecimal("700000")) == 0));
    }

    @Test
    @DisplayName("서브한도 하향은 항상 허용된다 — 합계가 이미 초과 상태여도 줄이는 방향은 막지 않는다")
    void lowerAlwaysAllowed() {
        stubActiveAccount(new BigDecimal("1000000"));
        stubCard(10L, 1L, new BigDecimal("600000"));
        // 클램프 이력 등으로 합계가 마스터를 이미 넘긴 상황을 가정한다.
        when(loadCardPort.sumActiveSubLimits(1L)).thenReturn(new BigDecimal("1200000"));

        service.change(new ChangeSubLimitCommand(10L, new BigDecimal("300000"), 100L));

        verify(saveCardPort).save(argThat(c -> c.getSubLimit().compareTo(new BigDecimal("300000")) == 0));
    }

    @Test
    @DisplayName("CANCELED 카드의 한도는 바꿀 수 없다")
    void canceledCardLimitImmutable() {
        stubActiveAccount(new BigDecimal("1000000"));
        Card canceled = Card.issue(1L, 1L, "m", new BigDecimal("100000"));
        canceled.cancel();
        when(loadCardPort.findById(10L)).thenReturn(Optional.of(canceled));

        assertThatThrownBy(() -> service.change(
                new ChangeSubLimitCommand(10L, new BigDecimal("200000"), 100L)))
                .isInstanceOf(InvalidCardTransitionException.class);
        verify(saveCardPort, never()).save(any());
    }

    @Test
    @DisplayName("정지된 카드의 한도는 바꿀 수 있다 — 복직 전에 미리 조정하는 경로")
    void suspendedCardLimitIsMutable() {
        stubActiveAccount(new BigDecimal("1000000"));
        Card suspended = Card.issue(1L, 1L, "m", new BigDecimal("600000"));
        suspended.suspend();
        when(loadCardPort.findById(10L)).thenReturn(Optional.of(suspended));
        when(loadCardPort.sumActiveSubLimits(1L)).thenReturn(new BigDecimal("600000"));

        service.change(new ChangeSubLimitCommand(10L, new BigDecimal("400000"), 100L));

        verify(saveCardPort).save(argThat(c -> c.getSubLimit().compareTo(new BigDecimal("400000")) == 0));
    }

    @Test
    @DisplayName("카드 정지는 서브한도 합계를 줄이지 않는다 — 재개 시 다른 카드와 충돌하지 않기 위해")
    void suspendingDoesNotFreeLimit() {
        stubActiveAccount(new BigDecimal("1000000"));
        Card card = Card.issue(1L, 1L, "m", new BigDecimal("600000"));
        when(loadCardPort.findById(10L)).thenReturn(Optional.of(card));
        when(loadCardPort.sumActiveSubLimits(1L)).thenReturn(new BigDecimal("600000"));

        statusService.change(new ChangeCardStatusCommand(10L, CardStatus.SUSPENDED, "휴직", 100L));

        // sumActiveSubLimits 는 status <> 'CANCELED' 기준이므로 정지 후에도 그대로다.
        assertThat(loadCardPort.sumActiveSubLimits(1L)).isEqualByComparingTo("600000");
    }
```

> 첫 테스트가 잡는 버그: 상향 검증에서 `sum + newLimit` 을 쓰면 자기 자신의 기존 한도가 이중 계상되어 정상 요청이 거부된다. `sum - currentSubLimit + newLimit` 이어야 한다.

- [x] **Step 2: 구현 → 통과 → 커밋**

```bash
git commit -am "feat(card): 서브한도·카드 상태 변경과 조회 API"
```

---

## Task 12: 조직 이탈자 카드 자동 정지

**Files:**

- Modify: `.../application/service/OrgProjectionService.java` (`removeMember` 에 카드 정지 연결)
- Test: `.../integration/MemberRemovedSuspendsCardIT.java`

**Interfaces:**

- Consumes: Task 7 `OrganizationMemberRemovedConsumer`, Task 4 `Card#suspend`(멱등)
- Produces: `member_removed` 수신 시 해당 임직원의 활성 카드가 `SUSPENDED` 로 전이되고 `card.status_changed` 발행

- [x] **Step 1: IT 작성 — 실패 먼저**

```java
    @Test
    @DisplayName("조직에서 제거된 임직원의 카드가 자동 정지된다")
    void memberRemovalSuspendsCard() {
        CardAccount account = saveActiveAccount(3001L, "777", new BigDecimal("1000000"));
        saveMemberProjection(3001L, 888L, OrgRole.STAFF);
        Card card = saveCardPort.save(Card.issue(account.getId(), 888L, "m", new BigDecimal("100000")));

        orgProjectionService.removeMember(3001L, 888L);

        assertThat(loadCardPort.findById(card.getId()))
                .map(Card::getStatus).contains(CardStatus.SUSPENDED);
        assertThat(loadOrgProjectionPort.findMemberRole(3001L, 888L)).isEmpty();
    }

    @Test
    @DisplayName("같은 이벤트를 두 번 받아도 안전하다 — 재처리·리플레이 멱등")
    void removalIsIdempotent() {
        CardAccount account = saveActiveAccount(3003L, "779", new BigDecimal("1000000"));
        saveMemberProjection(3003L, 888L, OrgRole.STAFF);
        Card card = saveCardPort.save(Card.issue(account.getId(), 888L, "m", new BigDecimal("100000")));

        orgProjectionService.removeMember(3003L, 888L);
        orgProjectionService.removeMember(3003L, 888L);   // 리플레이

        assertThat(loadCardPort.findById(card.getId()))
                .map(Card::getStatus).contains(CardStatus.SUSPENDED);
        // 상태가 이미 SUSPENDED 면 이벤트를 다시 내지 않는다 — 소비측 노이즈 방지.
        assertThat(outboxCountOf("CardStatusChanged")).isEqualTo(1);
    }

    @Test
    @DisplayName("정지돼도 서브한도 합계에는 계속 잡힌다 — 복직 시 한도 충돌 방지")
    void suspendedCardStillOccupiesLimit() {
        CardAccount account = saveActiveAccount(3004L, "780", new BigDecimal("1000000"));
        saveMemberProjection(3004L, 888L, OrgRole.STAFF);
        saveCardPort.save(Card.issue(account.getId(), 888L, "m", new BigDecimal("400000")));
        BigDecimal before = loadCardPort.sumActiveSubLimits(account.getId());

        orgProjectionService.removeMember(3004L, 888L);

        assertThat(loadCardPort.sumActiveSubLimits(account.getId()))
                .isEqualByComparingTo(before)
                .isEqualByComparingTo("400000");
    }

    @Test
    @DisplayName("카드가 없는 멤버 제거는 무해하다 — 프로젝션만 비활성화된다")
    void removalWithoutCardIsNoop() {
        saveActiveAccount(3005L, "781", new BigDecimal("1000000"));
        saveMemberProjection(3005L, 888L, OrgRole.STAFF);

        assertThatCode(() -> orgProjectionService.removeMember(3005L, 888L))
                .doesNotThrowAnyException();
        assertThat(loadOrgProjectionPort.findMemberRole(3005L, 888L)).isEmpty();
        assertThat(outboxCountOf("CardStatusChanged")).isZero();
    }
```

- [x] **Step 2: 구현 → 통과 → 커밋**

```bash
git commit -am "feat(card): 조직 이탈자 카드 자동 정지"
```

---

## Task 13: 일 1회 한도 재산정

**Files:**

- Create: `.../application/port/in/RecalculateCardLimitsUseCase.java` · `.../application/service/RecalculateCardLimitsService.java`
- Create: `.../adapter/in/schedule/CardLimitRecalculationScheduler.java`
- **Modify: `.../domain/CardAccount.java`** — 재심사 메서드 추가 (아래 참조)
- Test: `.../application/service/RecalculateCardLimitsServiceTest.java`
- Test: `.../integration/LimitRecalculationClampIT.java`

**Interfaces:**

- Consumes: Task 5 정책, Task 8 재원 조회, Task 4 `changeMasterLimit`
- Produces: `RecalculateCardLimitsUseCase.recalculateAll() : int` (변경 건수)
- **Produces: `CardAccount.rescreen(BigDecimal newLimit, LimitSnapshot snapshot, BigDecimal currentSubLimitSum) : LimitChangeResult`**

> **계획 결함 정정 (Task 6 리뷰에서 발견, 2026-08-02).** Task 4 가 만든 도메인에는 **ACTIVE 상태에서 `LimitSnapshot` 을 교체하는 public 메서드가 없다** — 스냅샷은 `activate()`/`reject()` 로 SCREENING 에서 나올 때만 설정된다. 그런데 재산정은 한도뿐 아니라 **산정 근거도 함께 갱신해야** 한다(근거가 옛 재원·옛 평판이면 `screened_at` 과 스냅샷이 서로 다른 심사를 가리키게 된다).
>
> `CardAccount.Builder` 로 새 인스턴스를 조립하는 우회는 쓰지 마라 — 빌더는 영속 계층의 재구성 전용이라 **상태 전이 가드를 건너뛰어** ACTIVE 가 아닌 계정도 임의로 재심사할 수 있게 된다. 도메인에 `rescreen(...)` 을 추가해 (a) ACTIVE 여야 하고, (b) 한도 변경은 기존 `changeMasterLimit` 의 클램프 규칙을 그대로 따르며, (c) 스냅샷은 non-null 이어야 한다는 불변식을 강제하라.
>
> 영속 계층은 이미 대비돼 있다 — `CardAccountPersistenceAdapter.save()` 가 스냅샷 5개 필드를 `compareTo` 로 비교해, 실제로 바뀐 재심사에서만 `screened_at` 을 갱신한다.

- [ ] **Step 1: 실패 테스트**

```java
    @Test
    @DisplayName("재원이 늘면 한도가 오른다")
    void raisesWhenFundingGrows() {
        CardAccount account = activeAccount(1L, "777", new BigDecimal("700000"));
        when(loadCardAccountPort.findAllActive()).thenReturn(List.of(account));
        when(loadCardPort.sumActiveSubLimits(1L)).thenReturn(new BigDecimal("500000"));
        when(loadSellerFundingPort.load("777"))
                .thenReturn(new SellerFunding(new BigDecimal("2000000"), BigDecimal.ZERO));
        when(loadReputationPort.gradeOf("777")).thenReturn(ReputationGrade.A);

        int changed = service.recalculateAll();

        assertThat(changed).isEqualTo(1);
        assertThat(account.getMasterLimit()).isEqualByComparingTo("1400000");
    }

    @Test
    @DisplayName("하향이 Σ서브한도 아래면 클램프하고 clamped=true 로 발행한다")
    void lowerIsClampedAndFlagged() {
        // 마스터 100만, Σ서브 80만, 재원 급감으로 산정 50만 → 80만으로 클램프
        ArgumentCaptor<LimitChangeResult> captor = ArgumentCaptor.forClass(LimitChangeResult.class);
        verify(publishCardEventPort).publishLimitChanged(any(), captor.capture());
        assertThat(captor.getValue().clamped()).isTrue();
        assertThat(captor.getValue().appliedLimit()).isEqualByComparingTo("800000");
    }

    @Test
    @DisplayName("한 계정의 재원 조회가 실패해도 나머지는 계속 처리한다")
    void oneFailureDoesNotAbortTheBatch() {
        when(loadSellerFundingPort.load("777")).thenThrow(new FundingUnavailableException("down", null));
        when(loadSellerFundingPort.load("778"))
                .thenReturn(new SellerFunding(new BigDecimal("2000000"), BigDecimal.ZERO));

        int changed = service.recalculateAll();

        assertThat(changed).isEqualTo(1);   // 778 만 반영
    }

    @Test
    @DisplayName("한도가 그대로면 이벤트를 발행하지 않는다 — 조용한 날은 조용해야 한다")
    void noEventWhenUnchanged() {
        CardAccount account = activeAccount(1L, "777", new BigDecimal("700000"));
        when(loadCardAccountPort.findAllActive()).thenReturn(List.of(account));
        when(loadCardPort.sumActiveSubLimits(1L)).thenReturn(BigDecimal.ZERO);
        // 같은 재원·등급 → 같은 한도 700,000
        when(loadSellerFundingPort.load("777"))
                .thenReturn(new SellerFunding(new BigDecimal("1000000"), BigDecimal.ZERO));
        when(loadReputationPort.gradeOf("777")).thenReturn(ReputationGrade.A);

        int changed = service.recalculateAll();

        assertThat(changed).isZero();
        verify(publishCardEventPort, never()).publishLimitChanged(any(), any());
    }

    @Test
    @DisplayName("ACTIVE 계정만 재산정한다 — findAllActive 외의 계정은 조회조차 하지 않는다")
    void onlyActiveAccountsAreRecalculated() {
        when(loadCardAccountPort.findAllActive()).thenReturn(List.of());

        int changed = service.recalculateAll();

        assertThat(changed).isZero();
        verify(loadSellerFundingPort, never()).load(anyString());
    }

    @Test
    @DisplayName("재산정으로 E등급이 되면 한도 0 이 아니라 계정을 SUSPENDED 로 돌린다")
    void gradeEDowngradeSuspendsAccount() {
        // 한도만 0 으로 만들면 카드가 남아있는 채로 사실상 무력화된다 —
        // 상태를 명시적으로 바꿔서 "왜 안 되는지"가 드러나게 한다.
        CardAccount account = activeAccount(1L, "777", new BigDecimal("700000"));
        when(loadCardAccountPort.findAllActive()).thenReturn(List.of(account));
        when(loadCardPort.sumActiveSubLimits(1L)).thenReturn(new BigDecimal("500000"));
        when(loadSellerFundingPort.load("777"))
                .thenReturn(new SellerFunding(new BigDecimal("1000000"), BigDecimal.ZERO));
        when(loadReputationPort.gradeOf("777")).thenReturn(ReputationGrade.E);

        service.recalculateAll();

        assertThat(account.getStatus()).isEqualTo(CardAccountStatus.SUSPENDED);
        // 한도는 클램프 규칙에 따라 Σ서브한도 아래로 내려가지 않는다.
        assertThat(account.getMasterLimit()).isEqualByComparingTo("500000");
        verify(publishCardEventPort).publishStatusChanged(any(), any());
    }
```

- [ ] **Step 2: 스케줄러 구현**

```java
    @Scheduled(cron = "${app.card.limit.recalculation-cron:0 30 3 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "card-limit-recalculation", lockAtMostFor = "PT30M")
    public void recalculate() {
        log.info("[CardLimitRecalc] 시작");
        int changed = useCase.recalculateAll();
        log.info("[CardLimitRecalc] 완료: 변경 {}건", changed);
        auditLogger.record(AuditAction.CARD_LIMIT_CHANGED, "CardLimitRecalcJob", "ALL",
                String.format("{\"changed\":%d}", changed));
    }
```

> `shedlock` 테이블은 Task 0 의 V2 에서 이미 만들었다. 없으면 다중 인스턴스에서 재산정이 중복 실행되어 한도가 요동친다.

- [ ] **Step 3: 통과 → 커밋**

```bash
git commit -am "feat(card): 일 1회 한도 재산정 — 상향 반영·하향 클램프"
```

---

## Task 14: 2단계 계약 선확정

**Files:**

- Create: `shared-common/src/testFixtures/resources/contracts/events/lemuel.card.authorized.schema.json` + 샘플
- Create: `shared-common/src/testFixtures/resources/contracts/events/lemuel.card.captured.schema.json` + 샘플
- Test: `card-service/src/test/java/github/lms/lemuel/card/contract/Phase2ContractPlaceholderTest.java`

**Interfaces:**

- Consumes: 없음
- Produces: 2단계 어댑터가 붙을 때 도메인 모델을 바꾸지 않아도 되는 계약

> 스펙 §3.4 의 합의 사항이다. 발행 코드는 1단계에 없지만 **스키마와 정본 샘플은 지금 확정**한다. 나중에 필드를 바꾸면 하위호환 규칙(ADR 0022) 때문에 신규 토픽 버전을 파야 한다.

- [ ] **Step 1: 스키마 작성**

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "lemuel.card.authorized",
  "description": "card-service → account-service (2단계). 카드 승인이 성립하면 발행된다. amount 는 JSON string(BigDecimal.toPlainString, DATA-STANDARD N5). remainingSubLimit 은 승인 후 잔여 서브한도로, 소비측이 자체 재계산 없이 검증에 쓸 수 있다. authorizationId 는 카드사 승인번호(mock 포함)로 멱등 자연키다.",
  "type": "object",
  "properties": {
    "authorizationId": { "type": "string" },
    "cardId": { "type": "integer" },
    "cardAccountId": { "type": "integer" },
    "holderUserId": { "type": "integer" },
    "amount": { "type": "string", "pattern": "^[0-9]+(\\.[0-9]+)?$" },
    "remainingSubLimit": {
      "type": "string",
      "pattern": "^[0-9]+(\\.[0-9]+)?$"
    },
    "merchantName": { "type": "string" },
    "authorizedAt": { "type": "string", "format": "date-time" }
  },
  "required": [
    "authorizationId",
    "cardId",
    "cardAccountId",
    "holderUserId",
    "amount",
    "authorizedAt"
  ],
  "additionalProperties": true
}
```

`lemuel.card.captured` 는 `captureId` · `authorizationId` · `cardId` · `amount` · `capturedAt` 필수.

- [ ] **Step 2: 스키마·샘플 정합 테스트**

```java
    @Test
    @DisplayName("2단계 계약의 정본 샘플이 스키마를 통과한다 — 발행 코드보다 계약이 먼저다")
    void phase2SamplesSatisfySchemas() {
        EventContractValidator.assertValid("lemuel.card.authorized",
                EventContractValidator.canonicalSample("lemuel.card.authorized"));
        EventContractValidator.assertValid("lemuel.card.captured",
                EventContractValidator.canonicalSample("lemuel.card.captured"));
    }
```

- [ ] **Step 3: 통과 → 커밋**

```bash
git commit -am "feat(card): 2단계 승인·매입 이벤트 계약 선확정"
```

---

## Task 15: 하네스·문서 배선과 최종 검증

**Files:**

- Create: `.claude/skills/card-service-rules/SKILL.md`
- Modify: `HARNESS.md`
- Modify: `SPEC.md`
- Modify: `README.md` (서비스 수 21 → 22)

**Interfaces:**

- Consumes: Task 0~14 전부
- Produces: `harness-audit.mjs` 통과, 문서 정합

- [ ] **Step 1: `card-service-rules` 스킬 작성**

다른 `*-domain-rules` 스킬 형식을 따른다. 반드시 담을 내용:

- 재원 정의(F 공식)와 **왜 account-service 에 물어보는가**
- `masterLimit >= Σ subLimit` 불변식과 비관적 락이 그것을 지키는 유일한 수단이라는 점
- 하향 클램프 규칙
- 재원 조회 실패에 폴백을 두지 않는 이유
- `sumActiveSubLimits` 가 SUSPENDED 를 포함하는 이유

- [ ] **Step 2: `HARNESS.md` 라우팅 행 추가 후 감사**

Run: `node scripts/harness/harness-audit.mjs`
Expected: 라우팅 dangling 0

- [ ] **Step 3: `SPEC.md` 갱신**

- §1 개요 표의 서비스 수 21 → 22, 도메인 목록에 "법인카드" 추가
- §5 토픽 표에 card 발행 4종 + 2단계 예약 2종, organization 신설 2종
- §3 에 `3.15 card-service` 절 신설 (다른 서비스 절과 같은 형식: 포트·DB·도메인 표·정책 요약)

- [ ] **Step 4: 전체 게이트 통과 확인**

```bash
./gradlew :shared-common:test \
          :account-service:test :account-service:jacocoTestCoverageVerification \
          :organization-service:test :organization-service:jacocoTestCoverageVerification \
          :card-service:test :card-service:jacocoTestCoverageVerification
```

Expected: 전부 PASS. **커버리지 미달 시 어서션을 완화하거나 제외 설정을 넓히지 않는다** — 테스트를 더 쓴다.

- [ ] **Step 5: 3층 경로 실검증**

```bash
docker compose up -d
TOKEN=$(curl -s -X POST localhost:8088/auth/login -H 'Content-Type: application/json' \
        -d '{"email":"...","password":"..."}' | jq -r .accessToken)

# 직접 포트 → gateway → nginx 순으로 같은 경로가 200 인지
curl -s -H "Authorization: Bearer $TOKEN" localhost:8106/api/cards/cards/me
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/cards/cards/me
curl -s -H "Authorization: Bearer $TOKEN" localhost:3000/api/cards/cards/me
```

- [ ] **Step 6: 종단 시나리오 수동 검증**

1. 셀러 조직 생성(organization) → OWNER 확인
2. 정산 확정 이벤트로 account 에 `SELLER_PAYABLE` 적재
3. `POST /api/cards/accounts` → 한도가 재원 × 0.70 × haircut 과 일치하는지
4. 임직원 초대·수락 → `POST /accounts/{id}/cards` 발급
5. 합계 초과 발급 시도 → 422
6. 임직원 제거(organization) → 카드가 SUSPENDED 로 바뀌는지
7. `GET /api/account/trial-balance` 로 카드 한도의 근거가 시산표와 맞는지 확인

- [ ] **Step 7: 최종 커밋**

```bash
git add .claude/skills/card-service-rules HARNESS.md SPEC.md README.md
git commit -m "docs(card): card-service 하네스·명세 배선"
```

---

## 완료 정의

- [ ] 16개 태스크 전부 커밋됨
- [ ] `./gradlew test` 전 모듈 통과 + JaCoCo LINE 90% 통과
- [ ] `CardIssuanceLimitConcurrencyIT` 두 케이스 모두 통과 (불변식 유지 + 처리량 미저해)
- [ ] `harness-audit.mjs` 통과
- [ ] 직접 포트·gateway·nginx 3층 모두 200
- [ ] 종단 시나리오 7단계 수동 확인
- [ ] 스펙 §2.1 의 한계(재원 이중 사용)가 `card-service-rules` 스킬에 기록되어 3단계 담당자에게 전달됨
