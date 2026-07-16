package github.lms.lemuel.investment.integration;

import github.lms.lemuel.InvestmentServiceApplication;
import github.lms.lemuel.investment.adapter.out.persistence.InvestmentManualIdempotencyGuard;
import github.lms.lemuel.investment.adapter.out.persistence.InvestmentManualOperationRecordRepository;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 투자 수동 REST 멱등 가드({@link InvestmentManualIdempotencyGuard})의 <b>실 PostgreSQL 관통</b> 검증.
 *
 * <p>단위테스트(목 기반)는 리포지토리가 던지는 {@code DataIntegrityViolationException} 을 흉내낼 뿐,
 * <b>실제 PK 유니크 제약</b>이 동시 선점(claim)을 정확히 하나만 통과시키는지는 검증하지 못한다. 이 IT 는
 * Testcontainers PostgreSQL(자체 Flyway 로 {@code investment_manual_operation_idempotency} 테이블 생성)에
 * 대해 가드를 그대로 실행해, PK 제약이 이중 조작을 원자적으로 차단함을 관통 확인한다.
 *
 * <p>검증 대상:
 * <ol>
 *   <li>동일 키 순차 2회 — 1번째만 선점 성공(true), 2번째는 PK 유니크 위반으로 거부(false), 행은 1개.</li>
 *   <li>동일 키 동시 2스레드 — 정확히 1개만 true(승자), 나머지는 false, 행은 1개(write-skew 없음).</li>
 *   <li>서로 다른 키 — 독립적으로 각각 선점 성공(모두 true).</li>
 * </ol>
 *
 * <p>Kafka 는 끄고(app.kafka.enabled=false) Flyway 를 켜 실제 스키마로 부팅한다. Docker 미가용 시 skip.
 */
@SpringBootTest(
        classes = InvestmentServiceApplication.class,
        properties = {
                "app.kafka.enabled=false",
                "spring.flyway.enabled=true",
                "spring.jpa.hibernate.ddl-auto=validate",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class InvestmentManualIdempotencyIntegrationTest {

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("lemuel_investment_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("POSTGRES_USER", POSTGRES::getUsername);
        registry.add("POSTGRES_PASSWORD", POSTGRES::getPassword);
    }

    @Autowired InvestmentManualIdempotencyGuard guard;
    @Autowired InvestmentManualOperationRecordRepository repository;

    @Test
    void 동일키_순차2회는_실PK제약으로_2번째가_거부된다() {
        String key = "seq-" + UUID.randomUUID();

        assertThat(guard.claim(key, "investment:execute:1", "op-1"))
                .as("최초 선점은 성공").isTrue();
        assertThat(guard.claim(key, "investment:execute:1", "op-1"))
                .as("동일 키 재선점은 PK 유니크 위반으로 거부").isFalse();

        assertThat(repository.existsById(key)).isTrue();
        assertThat(repository.findById(key)).isPresent();
    }

    @Test
    void 동일키_동시2스레드는_정확히_하나만_선점한다() throws Exception {
        String key = "conc-" + UUID.randomUUID();

        List<Boolean> results = claimConcurrently(key, "investment:cancel:9", 2);

        long winners = results.stream().filter(Boolean::booleanValue).count();
        assertThat(winners).as("실 PK 유니크 제약으로 정확히 1개만 승자").isEqualTo(1);
        assertThat(results).as("나머지는 거부").containsExactlyInAnyOrder(true, false);
        assertThat(repository.existsById(key)).as("행은 정확히 1개").isTrue();
    }

    @Test
    void 서로_다른키는_독립적으로_선점된다() {
        String keyA = "ind-a-" + UUID.randomUUID();
        String keyB = "ind-b-" + UUID.randomUUID();

        assertThat(guard.claim(keyA, "investment:place", "op-a")).isTrue();
        assertThat(guard.claim(keyB, "investment:place", "op-b")).isTrue();

        assertThat(repository.existsById(keyA)).isTrue();
        assertThat(repository.existsById(keyB)).isTrue();
    }

    /** 동일 키를 {@code threads} 개 스레드에서 동시에 선점하고, 각 스레드의 claim 결과를 모아 반환. */
    private List<Boolean> claimConcurrently(String key, String endpoint, int threads) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                String operator = "op-" + i;
                Callable<Boolean> task = () -> {
                    barrier.await();
                    return guard.claim(key, endpoint, operator);
                };
                futures.add(pool.submit(task));
            }
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> f : futures) {
                results.add(f.get());
            }
            return results;
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("동시 선점 태스크 실행 실패", e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }
}
