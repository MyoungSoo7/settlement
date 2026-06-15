---
name: settlement-integration-test
description: Write a Spring Boot @SpringBootTest integration test for settlement-service using Testcontainers PostgreSQL. Use when adding any integration test (real DB, JPA, repository, @Transactional service, concurrency/locking, event listener, end-to-end) under settlement-service. settlement-service is library-mode (no prod @SpringBootApplication), so tests need a test-scoped bootstrap and specific properties to boot in isolation. Triggers: "settlement 통합테스트", "settlement-service integration test", "Testcontainers", "정산 동시성 테스트", "@SpringBootTest settlement". NOTE: recipe assumes settlement-service is in library-mode (no prod @SpringBootApplication); discard/update when Phase B standalone boot is introduced.
tools: Read, Write, Edit, Bash
---

# settlement-service Integration Test Bootstrap

> ⚠️ **이 레시피는 settlement-service 가 현재 _라이브러리 모드_(prod `@SpringBootApplication` 없음,
> 마이그레이션은 order-service 책임)라는 전제에 묶여 있다.** Phase B 에서 standalone 부팅(자체
> `@SpringBootApplication` + 자체 Flyway 마이그레이션)이 도입되면 아래 부트스트랩·프로퍼티 레시피
> (test-scoped 부트스트랩, `flyway.enabled=false`, `ddl-auto=create-drop`, `default_schema=public` 등)는
> **폐기하거나 갱신해야 한다.** 전제가 바뀌었는지부터 확인하고 이 스킬을 적용하라.

settlement-service has **no production `@SpringBootApplication`** — `build.gradle.kts` runs it in
library mode (`bootJar` disabled; it is bundled into order-service's fat jar). To boot it in
isolation for `@SpringBootTest`, use the **test-scoped** bootstrap below. Never add a prod app class.

## 1. Test bootstrap (already exists — reuse, don't recreate)

`settlement-service/src/test/java/github/lms/lemuel/SettlementServiceApplication.java`

```java
@SpringBootApplication
public class SettlementServiceApplication {
    public static void main(String[] args) {
        org.springframework.boot.SpringApplication.run(SettlementServiceApplication.class, args);
    }
}
```

It scans `github.lms.lemuel` → settlement-service main + shared-common only (order-service is **not**
on the test classpath, so the MSA boundary holds). If it is missing, create it; otherwise reuse it.

## 2. Required `@SpringBootTest` setup

```java
@SpringBootTest(
        classes = SettlementServiceApplication.class,
        properties = {
                "spring.flyway.enabled=false",                          // migrations are order-service's job
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
