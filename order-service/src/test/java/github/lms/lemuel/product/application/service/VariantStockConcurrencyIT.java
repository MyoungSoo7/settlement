package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.adapter.out.persistence.ProductVariantPersistenceAdapter;
import github.lms.lemuel.product.adapter.out.persistence.SpringDataProductVariantRepository;
import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.application.port.out.SaveProductVariantPort;
import github.lms.lemuel.product.domain.ProductVariant;
import github.lms.lemuel.product.domain.exception.InsufficientStockException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SKU 재고 차감 동시성 통합 테스트.
 *
 * <p><b>핵심 시나리오</b>:
 * <pre>
 *   초기 재고: 50
 *   동시 요청: 100 개 스레드가 각각 1 개씩 차감 시도 (CountDownLatch 로 동시 시작)
 *   기대 결과:
 *     - 정확히 50 건 성공
 *     - 정확히 50 건 InsufficientStockException
 *     - 최종 재고: 0
 *     - 어떤 스레드도 음수 재고를 만들지 않음
 *     - Optimistic Lock 충돌은 retry 로 흡수되어 최종 결과는 일관적
 * </pre>
 *
 * <p>이 테스트가 통과한다는 것은 다음을 보장한다:
 * <ul>
 *   <li>JPA {@code @Version} 이 올바르게 동작</li>
 *   <li>{@link DecreaseVariantStockService} 의 retry 로직이 올바르게 동시성 충돌을 흡수</li>
 *   <li>도메인의 {@code stockQuantity < quantity} 체크가 race condition 에서도 깨지지 않음</li>
 * </ul>
 *
 * <p><b>실행 환경</b>: Testcontainers (Docker 필수). Docker 미가용 시 자동 실패 →
 * CI 환경에서만 활성화하거나 로컬에서 Docker Desktop 실행 후 수동 검증.
 */
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
@DataJpaTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ProductVariantPersistenceAdapter.class})
@ActiveProfiles("test")
class VariantStockConcurrencyIT {

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("inter")
            .withUsername("lemuel")
            .withPassword("lemuel");

    @DynamicPropertySource
    static void overrideDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired ProductVariantPersistenceAdapter persistenceAdapter;
    @Autowired SpringDataProductVariantRepository repository;
    @Autowired PlatformTransactionManager txManager;

    private DecreaseVariantStockService service;
    private Long variantId;

    @BeforeEach
    void setup() {
        // 사전 조건: products 테이블에 행 1 개 (FK 만족용) — Flyway 시드에 1 번 상품이 있다고 가정
        // 본 테스트는 V36 product_variants 의 product_id FK 만 만족시키면 되므로
        // V17 (seed_data.sql) 또는 V21 의 시드 상품 사용. 없으면 INSERT 직접.

        ProductVariant variant = ProductVariant.create(1L, "TEST-SKU-" + System.nanoTime(),
                "색상:빨강/사이즈:L", BigDecimal.ZERO, 50);
        ProductVariant saved = persistenceAdapter.save(variant);
        variantId = saved.getId();

        TransactionTemplate txTemplate = new TransactionTemplate(txManager);
        service = new DecreaseVariantStockService(persistenceAdapter, persistenceAdapter,
                txTemplate, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("동시 100 스레드가 재고 50 SKU 를 차감 — 정확히 50 건 성공, 50 건 InsufficientStock, 최종 재고 0")
    void concurrentDecrease_preservesIntegrity() throws Exception {
        final int threads = 100;
        final int initialStock = 50;
        final int decreasePerThread = 1;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger insufficientCount = new AtomicInteger();
        List<Throwable> unexpectedErrors = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();  // 모든 스레드 동시 시작
                    service.decrease(variantId, decreasePerThread);
                    successCount.incrementAndGet();
                } catch (InsufficientStockException e) {
                    insufficientCount.incrementAndGet();
                } catch (Throwable t) {
                    unexpectedErrors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();         // 폭발적 동시 시작
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).as("모든 스레드 30 초 내 완료").isTrue();
        assertThat(unexpectedErrors).as("재시도 한계 초과 또는 알 수 없는 오류 — Optimistic Lock 흡수 실패")
                .isEmpty();

        // 핵심 불변식 검증
        assertThat(successCount.get()).as("성공한 차감 수").isEqualTo(initialStock);
        assertThat(insufficientCount.get()).as("재고 부족으로 거절된 수")
                .isEqualTo(threads - initialStock);

        // DB 상의 최종 재고
        ProductVariant finalState = persistenceAdapter.loadById(variantId).orElseThrow();
        assertThat(finalState.getStockQuantity()).as("최종 재고는 정확히 0 — 음수가 아님").isZero();
        // 50 회 차감되었으므로 version 도 정확히 50 만큼 증가
        assertThat(finalState.getVersion())
                .as("Version 증가량은 성공 차감 횟수와 일치")
                .isEqualTo(initialStock);
    }
}
