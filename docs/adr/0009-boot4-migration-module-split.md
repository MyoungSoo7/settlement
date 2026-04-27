# ADR 0009 — Spring Boot 4.0 마이그레이션 + 모듈 분리 대응

**Status:** Accepted
**Date:** 2026-04-23

## Context

Boot 3.5 → 4.0 업그레이드 과정에서 다음 문제들이 드러났다:

1. `spring-boot-starter-aop` 가 Boot 4 BOM 에서 제거됨 (→ 빌드 실패).
2. `RestTemplateBuilder` 가 Boot 4에서 완전 제거됨 (TossPaymentService 컴파일 실패).
3. WebMvcTest 슬라이스 패키지 이동 (`.test.autoconfigure.web.servlet.*` → `.webmvc.test.autoconfigure.*`).
4. `@DataJpaTest` / `@AutoConfigureTestDatabase` 가 별도 모듈(`spring-boot-data-jpa-test`, `spring-boot-jdbc-test`)로 분리.
5. **`FlywayAutoConfiguration` 이 `spring-boot-flyway` 모듈로 분리** — 선언 안 하면 앱 기동 시 마이그레이션 자동 실행 안 됨 (운영 심각 이슈).
6. **`KafkaAutoConfiguration` 이 `spring-boot-kafka` 모듈로 분리** — `KafkaTemplate` 빈 미생성.
7. Jackson 3 (`tools.jackson.core`) 채택으로 `com.fasterxml.jackson.databind.ObjectMapper` 빈 미등록.
8. `spring-boot-starter-jackson-test` 추가 필요 (WebMvcTest 슬라이스용).

## Decision

**모든 autoconfig 는 명시적으로 declare** 하고, starter 가 있으면 starter 사용:

```kotlin
// 필수 분리 모듈
implementation("org.springframework.boot:spring-boot-flyway")
implementation("org.springframework.boot:spring-boot-starter-kafka")

// 테스트 슬라이스
testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
testImplementation("org.springframework.boot:spring-boot-starter-jackson-test")
```

**제거된 API 대응:**
- `RestTemplateBuilder` → `SimpleClientHttpRequestFactory` 로 직접 타임아웃 구성.
- `WebMvcConfigurer.configureMessageConverters(List)` → `configureMessageConverters(HttpMessageConverters.ServerBuilder)` fluent API.
- `MappingJackson2HttpMessageConverter` → `JacksonJsonHttpMessageConverter` (Spring 7).
- WebMvcTest 임포트 경로 갱신.

**Jackson 2 호환 브릿지:**
- 레거시 코드(`OutboxBackedEventPublisher`, `PaymentEventKafkaConsumer`)가 Jackson 2 `ObjectMapper` 를 주입받음.
- `JacksonCompatConfig` 에 `@Bean ObjectMapper` 수동 등록 — 모든 사용처 Jackson 3 로 이전되면 제거.

**테스트 슬라이스:**
- `@DataJpaTest` + `@ImportAutoConfiguration(FlywayAutoConfiguration.class)` — Flyway 가 슬라이스에 포함되지 않으므로 명시.
- WebMvcTest 에 `@WithMockUser` 대신 `with(user(...).roles(...))` 포스트프로세서 — Spring Security 6.x+ 의 `STATELESS` 세션 + `SecurityContextHolderFilter` 리셋 이슈 회피.

**격리된 이슈 (별도 작업):**
- `KafkaOutboxIntegrationTest` — SpringDoc OpenAPI 2.8.0 의 QuerydslProvider 가 Spring Data 의 구 `TypeInformation` 을 참조 → springdoc 버전 상향 필요.
- `BatchConfig.JobLauncher` — Spring Batch 6 에서 removal 예정, `JobOperator` 로 교체 작업 예정.

## Consequences

**Positive**
- 전체 테스트 265/265 중 263 pass, 2 skip (명시적 사유). 0 failure.
- 운영 배포에서 Flyway 가 자동 실행되는 문제가 해결됨 (이전엔 Gradle 플러그인 경유였을 가능성).
- Boot 4 + Spring 7 + Jackson 3 의 deprecation 14건 → 0건.

**Negative / Trade-offs**
- 모듈 선언이 세분화되어 build.gradle.kts 길어짐.
- Jackson 2/3 공존 브릿지 — 임시 부채.
- springdoc-openapi 업그레이드는 별도 작업.

## Related

- Boot 4 업그레이드 가이드 (Spring 공식)
- 이 프로젝트의 build.gradle.kts 주석 (버전 명시 각 라인)
