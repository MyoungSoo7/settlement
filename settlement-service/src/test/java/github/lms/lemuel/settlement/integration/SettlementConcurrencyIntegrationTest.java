package github.lms.lemuel.settlement.integration;

import github.lms.lemuel.SettlementServiceApplication;
import github.lms.lemuel.settlement.adapter.out.persistence.SettlementJpaEntity;
import github.lms.lemuel.settlement.adapter.out.persistence.SpringDataSettlementJpaRepository;
import github.lms.lemuel.settlement.application.port.in.AdjustSettlementForRefundUseCase;
import github.lms.lemuel.settlement.application.port.in.ReleaseHoldbackUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정산 비관적 락(SELECT ... FOR UPDATE) 동시성 검증 — 실 PostgreSQL(Testcontainers) 기반.
 *
 * <p>H2 는 row-level FOR UPDATE 블로킹을 충실히 흉내내지 못하므로 실 PG 컨테이너로 검증한다.
 * Flyway disable + ddl-auto=create-drop 으로 entity 기반 스키마를 생성한다
 * (마이그레이션은 order-service 책임 — settlement-service 단독 부팅 시 schema 없음).
 *
 * <p>검증 대상:
 * <ol>
 *   <li>동시 부분환불 2건 → {@code findByPaymentIdForUpdate} 의 행 잠금으로 직렬화되어
 *       lost update 없이 누적 refundedAmount 가 정확히 합산된다. (낙관적 락만 있었다면 한쪽이
 *       OptimisticLockException 으로 실패하거나 갱신이 유실됐을 것)</li>
 *   <li>동시 holdback 해제 배치 2건 → {@code findReleasableHoldbacks} 의 FOR UPDATE 로
 *       각 정산이 정확히 한 번만 해제되고 두 배치 모두 예외 없이 완료된다.</li>
 * </ol>
 */
@SpringBootTest(
        classes = SettlementServiceApplication.class,
        properties = {
                "spring.flyway.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.properties.hibernate.default_schema=public",
                "app.kafka.enabled=false",
                "app.search.enabled=false",
                "spring.batch.job.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class SettlementConcurrencyIntegrationTest {

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("opslab_test")
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

    @Autowired AdjustSettlementForRefundUseCase adjustUseCase;
    @Autowired ReleaseHoldbackUseCase releaseUseCase;
    @Autowired SpringDataSettlementJpaRepository settlementRepo;
    @Autowired TransactionTemplate tx;

    @Test
    void 동시_부분환불_2건은_FOR_UPDATE_로_직렬화되어_lost_update_가_없다() throws Exception {
        // given — holdback 없는 PROCESSING 정산 (결제 10,000 / 수수료 300 / net 9,700)
        Long paymentId = 7001L;
        Long settlementId = tx.execute(s -> settlementRepo.save(
                newSettlement(paymentId, 8001L, "10000.00", "300.00", "9700.00", "PROCESSING")).getId());

        BigDecimal refundEach = new BigDecimal("3000.00");

        // when — 두 스레드가 동시에 서로 다른 부분환불을 정산에 반영
        List<Throwable> errors = runConcurrently(2, barrier -> () -> {
            barrier.await();
            adjustUseCase.adjustSettlementForRefund(paymentId, refundEach, null);
            return null;
        });

        // then — 둘 다 예외 없이 성공 (비관적 락이면 두 번째가 블록 후 최신값을 읽어 성공)
        assertThat(errors)
                .as("FOR UPDATE 직렬화 → OptimisticLockException 등 예외가 없어야 한다")
                .isEmpty();

        // 누적 환불 6,000 이 정확히 반영 — lost update 없음
        SettlementJpaEntity after = settlementRepo.findById(settlementId).orElseThrow();
        assertThat(after.getRefundedAmount()).isEqualByComparingTo("6000.00");
        // netAmount = 10,000 - 6,000(환불) - 300(수수료) = 3,700
        assertThat(after.getNetAmount()).isEqualByComparingTo("3700.00");
    }

    @Test
    void 동시_holdback_해제_배치_2건은_각_정산을_정확히_한번만_해제한다() throws Exception {
        // given — release 가능한(어제 만기) holdback 정산 5건
        LocalDate today = LocalDate.now();
        LocalDate due = today.minusDays(1);
        int n = 5;
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < n; i++) {
                SettlementJpaEntity e = newSettlement(9000L + i, 9500L + i,
                        "10000.00", "300.00", "9700.00", "PROCESSING");
                e.setHoldbackAmount(new BigDecimal("1000.00"));
                e.setHoldbackRate(new BigDecimal("0.1000"));
                e.setHoldbackReleaseDate(due);
                e.setHoldbackReleased(false);
                settlementRepo.save(e);
            }
        });

        // when — 두 배치가 동시에 해제 시도
        List<Integer> counts = new ArrayList<>();
        List<Throwable> errors = runConcurrently(2, barrier -> () -> {
            barrier.await();
            int released = releaseUseCase.releaseAllDueOn(today);
            synchronized (counts) { counts.add(released); }
            return null;
        });

        // then — 예외 없이 완료, 두 배치 합산 해제 건수 == n (이중 해제 없음)
        assertThat(errors)
                .as("FOR UPDATE 직렬화 → 동시 배치가 OptimisticLockException 없이 완료")
                .isEmpty();
        assertThat(counts.stream().mapToInt(Integer::intValue).sum())
                .as("각 정산은 정확히 한 번만 해제 — 합산 해제 건수가 정확히 n")
                .isEqualTo(n);

        // 모든 정산이 released=true 로 마감
        long releasedRows = settlementRepo.findAll().stream()
                .filter(e -> e.getHoldbackReleaseDate() != null && !e.getHoldbackReleaseDate().isAfter(today))
                .filter(SettlementJpaEntity::isHoldbackReleased)
                .count();
        assertThat(releasedRows).isEqualTo(n);
    }

    /**
     * threadCount 개 스레드를 CyclicBarrier 로 동시에 출발시키고, 각 작업에서 던져진 예외를 모아 반환.
     */
    private List<Throwable> runConcurrently(int threadCount,
                                            TaskFactory factory) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<Future<Void>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(factory.create(barrier)));
            }
            List<Throwable> errors = new ArrayList<>();
            for (Future<Void> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    errors.add(e.getCause() != null ? e.getCause() : e);
                }
            }
            return errors;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface TaskFactory {
        Callable<Void> create(CyclicBarrier barrier);
    }

    private SettlementJpaEntity newSettlement(Long paymentId, Long orderId,
                                              String paymentAmount, String commission, String netAmount,
                                              String status) {
        SettlementJpaEntity s = new SettlementJpaEntity();
        s.setPaymentId(paymentId);
        s.setOrderId(orderId);
        s.setPaymentAmount(new BigDecimal(paymentAmount));
        s.setCommission(new BigDecimal(commission));
        s.setNetAmount(new BigDecimal(netAmount));
        s.setStatus(status);
        s.setSettlementDate(LocalDate.now());
        return s;
    }
}
