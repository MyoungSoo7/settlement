---
name: settlement-integration-test
description: Write a Spring Boot @SpringBootTest integration test for settlement-service using Testcontainers PostgreSQL. Use when adding any integration test (real DB, JPA, repository, @Transactional service, concurrency/locking, event listener, end-to-end) under settlement-service. settlement-service is standalone (prod @SpringBootApplication in main, own settlement_db + Flyway V1 baseline) but tests still boot with flyway off + create-drop. Triggers: "settlement 통합테스트", "settlement-service integration test", "Testcontainers", "정산 동시성 테스트", "@SpringBootTest settlement".
tools: Read, Write, Edit, Bash
---

# settlement-service Integration Test Bootstrap

> ℹ️ **2026-07-07 갱신**: settlement-service 는 이제 **standalone** 이다 — prod
> `@SpringBootApplication` 이 main 에 있고(`github.lms.lemuel.SettlementServiceApplication`),
> 자체 DB(settlement_db) + 자체 Flyway(V1 베이스라인)를 소유한다 (ADR 0020).
> 그래도 테스트 레시피는 그대로 유효하다: 테스트는 Flyway 를 끄고 엔티티 기반
> `create-drop` 으로 스키마를 만들어 컨테이너 1개로 격리 부팅한다.

## 1. Bootstrap — main 의 prod 애플리케이션 클래스를 그대로 사용

`settlement-service/src/main/java/github/lms/lemuel/SettlementServiceApplication.java` 를
`@SpringBootTest(classes = ...)` 로 지정한다. 별도 test-scoped 부트스트랩을 만들지 마라.

It scans `github.lms.lemuel` → settlement-service main + shared-common only (order-service is **not**
on the test classpath, so the MSA boundary holds).

## 2. Required `@SpringBootTest` setup

```java
@SpringBootTest(
        classes = SettlementServiceApplication.class,
        properties = {
                "spring.flyway.enabled=false",                          // 테스트는 엔티티 기반 스키마 사용 (Flyway 미적용)
                "spring.jpa.hibernate.ddl-auto=create-drop",            // build schema from entities
                "spring.jpa.properties.hibernate.default_schema=public",// override yml's opslab schema
                "app.kafka.enabled=false",
                "app.search.enabled=false",
                "spring.batch.job.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK" // JwtUtil needs >=32 bytes
        }
)
@Testcontainers
class XxxIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("opslab_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("POSTGRES_USER", POSTGRES::getUsername);     // yml references these env vars
        r.add("POSTGRES_PASSWORD", POSTGRES::getPassword);
    }
}
```

Why each property matters:
- **Flyway off + ddl-auto=create-drop**: settlement-service shares order-service's DB and owns no
  migrations; on its own there is no schema, so Hibernate builds it from entities.
- **default_schema=public**: `application.yml` pins `opslab`; the container has only `public`.
- **kafka/search/batch off**: avoids needing external brokers/ES; these are `@ConditionalOnProperty`-gated.
- **jwt secret**: security config fails fast on a short HS256 key.

`TransactionTemplate` is auto-available (Spring Boot `TransactionAutoConfiguration`) — `@Autowired` it
for setup inserts. The test method is **not** transactional, so worker-thread commits are visible to
later reads.

## 3. Concurrency / locking tests (pessimistic & optimistic)

Use **real PostgreSQL** — H2 does not faithfully block on `SELECT ... FOR UPDATE`. Pattern:
- Insert the row via `TransactionTemplate`.
- Launch N worker threads, gated by a `CyclicBarrier` so they start together.
- In each worker call the **`@Transactional` service method directly** — each call opens its own tx,
  so the row lock is exercised across real connections (ensure Hikari pool ≥ N+1).
- Collect exceptions from `Future.get()`. With a pessimistic lock, all calls succeed (serialized);
  with optimistic-only (`@Version`) a loser throws `OptimisticLockException` — assert the list is empty
  and the aggregate state (e.g. cumulative `refundedAmount`) proves no lost update.

## 4. Canonical examples in this repo

- `settlement/integration/SettlementConcurrencyIntegrationTest.java` — pessimistic lock concurrency.
- `ledger/integration/LedgerEndToEndIntegrationTest.java` — `@TransactionalEventListener(AFTER_COMMIT)`
  + `@Async` end-to-end with Awaitility.
- `settlement/adapter/in/kafka/DlqEndToEndTest.java` — narrow `@SpringBootConfiguration` + `@EmbeddedKafka`
  when you want a Kafka slice **without** the full context.

Prefer the narrow `@SpringBootConfiguration` slice (DlqEndToEndTest style) when you only need a couple
of beans; use the full `SettlementServiceApplication` bootstrap when you need JPA + services wired.

## 5. Building (Java 25 toolchain is Windows-side)

This repo's JDK 25 lives on the Windows host, not WSL. Run Gradle through the Windows wrapper:

```bash
cmd.exe /c "gradlew.bat :settlement-service:test --tests github.lms.lemuel.<pkg>.<Class>"
```

Testcontainers needs Docker (`docker ps` to confirm). Harmless shutdown noise — `HikariPool ... Failed
to validate connection` / `create-drop` DROP errors — appears when the container stops before the JPA
EMF closes; ignore it if the build is `BUILD SUCCESSFUL`. Verify real pass counts in
`settlement-service/build/test-results/test/TEST-*.xml` (`tests=`/`failures=`/`errors=`).
