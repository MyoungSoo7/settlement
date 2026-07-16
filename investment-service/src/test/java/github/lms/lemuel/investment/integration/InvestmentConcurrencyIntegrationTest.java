package github.lms.lemuel.investment.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.InvestmentServiceApplication;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.investment.adapter.in.kafka.SettlementConfirmedConsumer;
import github.lms.lemuel.investment.adapter.out.persistence.InvestmentOrderJpaEntity;
import github.lms.lemuel.investment.adapter.out.persistence.InvestmentOrderRepository;
import github.lms.lemuel.investment.adapter.out.persistence.SellerFundingViewJpaEntity;
import github.lms.lemuel.investment.adapter.out.persistence.SellerFundingViewRepository;
import github.lms.lemuel.investment.application.exception.InsufficientFundingException;
import github.lms.lemuel.investment.application.port.in.ExecuteInvestmentOrderUseCase;
import github.lms.lemuel.investment.application.port.in.IngestConfirmedSettlementUseCase;
import github.lms.lemuel.investment.domain.FundingViewStatus;
import github.lms.lemuel.investment.domain.InvestmentOrderStatus;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 투자 서비스 동시성 + 멱등 검증 — 실 PostgreSQL(Testcontainers) 기반.
 *
 * <p>H2 는 row-level FOR UPDATE 블로킹을 충실히 흉내내지 못하므로 실 PG 컨테이너로 검증한다.
 * investment 는 자체 마이그레이션을 소유하므로 Flyway 를 켜서 실제 스키마(opslab, @Version 컬럼 포함)로
 * 부팅한다. Kafka 는 끄고(app.kafka.enabled=false) Outbox 는 in-process 로 소비된다.
 *
 * <p>검증 대상:
 * <ol>
 *   <li>★ 같은 셀러 두 주문 동시 execute → seller_funding_view FOR UPDATE 직렬화로 재원 내에서만
 *       집행되고, 초과분은 정확히 REJECTED 된다(락 없는 평문 집계였다면 둘 다 통과해 재원 초과 집행).</li>
 *   <li>settlement.confirmed 동일 이벤트 재소비 시 processed_events 멱등으로 seller_funding_view 중복
 *       적재가 0 이고, 같은 settlementId 재적재도 UPSERT 로 1 행을 유지한다.</li>
 * </ol>
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
class InvestmentConcurrencyIntegrationTest {

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

    @Autowired ExecuteInvestmentOrderUseCase executeUseCase;
    @Autowired IngestConfirmedSettlementUseCase ingestUseCase;
    @Autowired InvestmentOrderRepository orderRepository;
    @Autowired SellerFundingViewRepository fundingRepository;
    @Autowired ProcessedEventRepository processedEventRepository;
    @Autowired ObjectMapper objectMapper;
    @Autowired TransactionTemplate tx;

    @Test
    void 같은셀러_두주문_동시집행은_재원내에서만_집행되고_초과분은_REJECTED된다() throws Exception {
        long sellerId = 700_001L;
        // given — 확정 재원 1,000,000 + 각 600,000 짜리 REQUESTED 주문 2건(합 1,200,000 > 재원)
        tx.executeWithoutResult(s -> {
            fundingRepository.save(new SellerFundingViewJpaEntity(
                    810_001L, sellerId, new BigDecimal("1000000.00"),
                    FundingViewStatus.CONFIRMED, LocalDateTime.now()));
        });
        long orderIdA = seedRequestedOrder(sellerId, "600000.00");
        long orderIdB = seedRequestedOrder(sellerId, "600000.00");

        // when — 두 스레드가 동시에 각자의 주문을 집행
        List<Throwable> errors = runConcurrently(List.of(orderIdA, orderIdB), sellerId);

        // then — 정확히 한쪽만 EXECUTED, 다른 쪽은 재원 부족으로 REJECTED + InsufficientFundingException 1건
        long executed = orderRepository.findBySellerIdOrderByIdAsc(sellerId).stream()
                .filter(o -> o.getStatus() == InvestmentOrderStatus.EXECUTED).count();
        long rejected = orderRepository.findBySellerIdOrderByIdAsc(sellerId).stream()
                .filter(o -> o.getStatus() == InvestmentOrderStatus.REJECTED).count();
        assertThat(executed).as("재원 내에서 정확히 1건만 집행").isEqualTo(1);
        assertThat(rejected).as("초과분은 REJECTED").isEqualTo(1);

        assertThat(errors).as("초과 집행 시도 1건이 InsufficientFundingException").hasSize(1);
        assertThat(errors.get(0)).isInstanceOf(InsufficientFundingException.class);

        // 집행된 총액은 재원(1,000,000)을 넘지 않는다 — write-skew 없음
        BigDecimal executedSum = orderRepository.sumBySellerAndStatus(sellerId, InvestmentOrderStatus.EXECUTED);
        assertThat(executedSum).isEqualByComparingTo("600000.00");
        assertThat(executedSum).isLessThanOrEqualTo(new BigDecimal("1000000.00"));
    }

    @Test
    void settlement_confirmed_재소비는_processed_events_멱등으로_중복적재가_없다() {
        long sellerId = 700_002L;
        long settlementId = 820_002L;
        UUID eventId = UUID.randomUUID();
        String payload = "{\"settlementId\":" + settlementId
                + ",\"sellerId\":" + sellerId + ",\"amount\":\"500000.00\"}";

        SettlementConfirmedConsumer consumer = new SettlementConfirmedConsumer(
                ingestUseCase, processedEventRepository, objectMapper);

        // 1) 최초 소비 → 재원 1행 적재
        consumer.onSettlementConfirmed(record(eventId, payload), mock(Acknowledgment.class));
        // 2) 동일 event_id 재소비 → processed_events 멱등으로 스킵(중복 적재 없음)
        consumer.onSettlementConfirmed(record(eventId, payload), mock(Acknowledgment.class));
        // 3) 다른 event_id·같은 settlementId → 프로젝션 UPSERT 로 여전히 1행(합계 불변)
        consumer.onSettlementConfirmed(record(UUID.randomUUID(), payload), mock(Acknowledgment.class));

        long rows = fundingRepository.findAll().stream()
                .filter(v -> v.getSellerId().equals(sellerId)).count();
        assertThat(rows).as("동일 settlementId 는 정확히 1행").isEqualTo(1);

        BigDecimal confirmed = fundingRepository.sumBySellerAndStatus(sellerId, FundingViewStatus.CONFIRMED);
        assertThat(confirmed).as("중복 적재 없이 500,000").isEqualByComparingTo("500000.00");
    }

    private long seedRequestedOrder(long sellerId, String amount) {
        return tx.execute(s -> orderRepository.save(new InvestmentOrderJpaEntity(
                null, sellerId, "005930", new BigDecimal(amount),
                82, "AA", InvestmentOrderStatus.REQUESTED, LocalDateTime.now(), null)).getId());
    }

    private static ConsumerRecord<String, String> record(UUID eventId, String payload) {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("lemuel.settlement.confirmed", 0, 0L, null, payload);
        record.headers().add("event_id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    /** 각 orderId 를 별도 스레드에서 동시에 execute 하고, 던져진 예외를 모아 반환. */
    private List<Throwable> runConcurrently(List<Long> orderIds, long sellerId) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(orderIds.size());
        CyclicBarrier barrier = new CyclicBarrier(orderIds.size());
        List<Future<Void>> futures = new ArrayList<>();
        try {
            for (Long orderId : orderIds) {
                Callable<Void> task = () -> {
                    barrier.await();
                    executeUseCase.execute(orderId, sellerId);
                    return null;
                };
                futures.add(pool.submit(task));
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
}
