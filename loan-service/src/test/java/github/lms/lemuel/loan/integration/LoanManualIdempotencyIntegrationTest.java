package github.lms.lemuel.loan.integration;

import github.lms.lemuel.LoanServiceApplication;
import github.lms.lemuel.loan.adapter.out.persistence.LoanManualIdempotencyGuard;
import github.lms.lemuel.loan.adapter.out.persistence.LoanManualOperationRecordRepository;
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
 * 기업대출 수동 상환 멱등 가드({@link LoanManualIdempotencyGuard})의 <b>실 PostgreSQL 관통</b> 검증.
 *
 * <p>단위테스트(목 기반)는 리포지토리 반환값을 흉내낼 뿐, <b>실제 PK 유니크 제약</b>이 순차·동시 선점(claim)을
 * 정확히 하나만 통과시키는지는 검증하지 못한다. 이 IT 는 Testcontainers PostgreSQL(자체 Flyway 로
 * {@code loan_manual_operation_idempotency} 테이블 생성)에 대해 가드를 그대로 실행해, 순차 재제출의 두 번째
 * 상환이 원자적으로 차단됨(#4)을 관통 확인한다. Docker 미가용 시 skip.
 */
@SpringBootTest(
        classes = LoanServiceApplication.class,
        properties = {
                "app.kafka.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class LoanManualIdempotencyIntegrationTest {

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("loan_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("POSTGRES_USER", POSTGRES::getUsername);
        r.add("POSTGRES_PASSWORD", POSTGRES::getPassword);
    }

    @Autowired LoanManualIdempotencyGuard guard;
    @Autowired LoanManualOperationRecordRepository repository;

    @Test
    void 동일키_순차2회는_실PK제약으로_2번째가_거부된다() {
        String key = "seq-" + UUID.randomUUID();

        assertThat(guard.claim(key, "loan:corporate:repay:1", "op-1"))
                .as("최초 선점은 성공(상환 진행)").isTrue();
        assertThat(guard.claim(key, "loan:corporate:repay:1", "op-1"))
                .as("동일 키 재선점은 PK 유니크 위반으로 거부(2번째 차감 차단)").isFalse();

        assertThat(repository.existsById(key)).isTrue();
    }

    @Test
    void 동일키_동시2스레드는_정확히_하나만_선점한다() throws Exception {
        String key = "conc-" + UUID.randomUUID();

        List<Boolean> results = claimConcurrently(key, "loan:corporate:repay:9", 2);

        long winners = results.stream().filter(Boolean::booleanValue).count();
        assertThat(winners).as("실 PK 유니크 제약으로 정확히 1개만 승자").isEqualTo(1);
        assertThat(results).as("나머지는 거부").containsExactlyInAnyOrder(true, false);
        assertThat(repository.existsById(key)).as("행은 정확히 1개").isTrue();
    }

    @Test
    void 서로_다른키는_독립적으로_선점된다() {
        String keyA = "ind-a-" + UUID.randomUUID();
        String keyB = "ind-b-" + UUID.randomUUID();

        assertThat(guard.claim(keyA, "loan:corporate:repay:2", "op-a")).isTrue();
        assertThat(guard.claim(keyB, "loan:corporate:repay:3", "op-b")).isTrue();

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
