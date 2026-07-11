package github.lms.lemuel.settlement.integration;

import github.lms.lemuel.SettlementServiceApplication;
import github.lms.lemuel.settlement.adapter.out.persistence.DailyTotalsJdbcAdapter;
import github.lms.lemuel.settlement.adapter.out.persistence.SellerMetaProjectionAdapter;
import github.lms.lemuel.settlement.adapter.out.persistence.SettlementPersistenceAdapter;
import github.lms.lemuel.report.adapter.out.persistence.PeriodReconciliationJdbcAdapter;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementCycle;
import github.lms.lemuel.settlement.domain.SettlementStatus;
import github.lms.lemuel.settlement.domain.SellerTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * settlement 소유 영속성 어댑터(어그리게이트 저장/조회, 셀러 메타 프로젝션, 자기-DB 집계)를
 * 실제 PostgreSQL 로 검증한다. order 내부 API 에 의존하는 집계 메서드는 대상에서 제외하고
 * settlement_db 만 읽는 경로를 태운다.
 */
@SpringBootTest(
        classes = SettlementServiceApplication.class,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration",
                "spring.flyway.schemas=public",
                "spring.flyway.default-schema=public",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.properties.hibernate.default_schema=public",
                "app.kafka.enabled=false",
                "app.search.enabled=false",
                "spring.batch.job.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class SettlementPersistenceAdaptersIT {

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> SETTLEMENT_DB = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("settlement_db").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", SETTLEMENT_DB::getJdbcUrl);
        r.add("spring.datasource.username", SETTLEMENT_DB::getUsername);
        r.add("spring.datasource.password", SETTLEMENT_DB::getPassword);
    }

    private static final LocalDate D = LocalDate.of(2026, 6, 20);

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired SettlementPersistenceAdapter settlementAdapter;
    @Autowired DailyTotalsJdbcAdapter dailyTotalsAdapter;
    @Autowired PeriodReconciliationJdbcAdapter periodReconAdapter;
    @Autowired SellerMetaProjectionAdapter sellerMetaAdapter;

    @BeforeEach
    void clean() {
        // 정산 이력 테이블은 immutable-history-guard 가 DELETE 금지 — 테스트 격리 초기화는 TRUNCATE 로.
        jdbc.execute("TRUNCATE TABLE settlement_adjustments, settlements RESTART IDENTITY CASCADE");
        jdbc.update("DELETE FROM settlement_payment_view");
    }

    @Test
    @DisplayName("SettlementPersistenceAdapter: save/find/saveAll/date·status 조회")
    void save_and_find() {
        Settlement s = Settlement.createFromPayment(9001L, 8001L, new BigDecimal("1000.00"), D);
        Settlement saved = settlementAdapter.save(s);
        assertThat(saved.getId()).isNotNull();

        assertThat(settlementAdapter.findById(saved.getId())).isPresent();
        assertThat(settlementAdapter.findByPaymentId(9001L)).isPresent();
        // FOR UPDATE 잠금 쿼리는 활성 트랜잭션 필요
        Optional<Settlement> locked = tx.execute(st -> settlementAdapter.findByPaymentIdForUpdate(9001L));
        assertThat(locked).isPresent();
        assertThat(settlementAdapter.findBySettlementDate(D)).hasSize(1);
        assertThat(settlementAdapter.findBySettlementDateAndStatus(D, SettlementStatus.REQUESTED)).hasSize(1);
        // REQUESTED 는 확정 대상
        List<Settlement> confirmable = tx.execute(st -> settlementAdapter.findConfirmableForUpdate(D, 10));
        assertThat(confirmable).hasSize(1);

        // saveAll
        Settlement s2 = Settlement.createFromPayment(9002L, 8002L, new BigDecimal("2000.00"), D);
        List<Settlement> savedAll = settlementAdapter.saveAll(List.of(s2));
        assertThat(savedAll).hasSize(1);
        assertThat(settlementAdapter.findBySettlementDate(D)).hasSize(2);
    }

    @Test
    @DisplayName("SettlementPersistenceAdapter: 홀드백 releasable 조회")
    void releasable_holdback() {
        Settlement s = Settlement.createFromPayment(9010L, 8010L, new BigDecimal("1000.00"), D);
        s.applyHoldback(new BigDecimal("0.30"), D.minusDays(1)); // 이미 release 예정일 지남
        settlementAdapter.save(s);

        List<Settlement> releasable = tx.execute(st -> settlementAdapter.findReleasableOn(D, 10));
        assertThat(releasable).hasSize(1);
    }

    @Test
    @DisplayName("DailyTotalsJdbcAdapter: 자기-DB gross/refunded/count 집계")
    void daily_totals_selfDb() {
        settlementAdapter.save(Settlement.createFromPayment(9020L, 8020L, new BigDecimal("1500.00"), D));

        assertThat(dailyTotalsAdapter.sumSettlementGross(LocalDate.now())).isEqualByComparingTo("1500.00");
        assertThat(dailyTotalsAdapter.sumSettlementRefunded(LocalDate.now())).isEqualByComparingTo("0.00");
        assertThat(dailyTotalsAdapter.countSettlementsCreated(LocalDate.now())).isEqualTo(1L);
    }

    @Test
    @DisplayName("PeriodReconciliationJdbcAdapter: 자기-DB net/commission/count 집계")
    void period_recon_selfDb() {
        settlementAdapter.save(Settlement.createFromPayment(9030L, 8030L, new BigDecimal("1000.00"), D));

        assertThat(periodReconAdapter.sumSettlementNet(D, D)).isEqualByComparingTo("970.00");
        assertThat(periodReconAdapter.sumSettlementCommission(D, D)).isEqualByComparingTo("30.00");
        assertThat(periodReconAdapter.sumAdjustmentsAbsolute(D, D)).isEqualByComparingTo("0");
        assertThat(periodReconAdapter.countSettlementsCreated(LocalDate.now(), LocalDate.now())).isEqualTo(1L);
    }

    @Test
    @DisplayName("SellerMetaProjectionAdapter: payment_view 에서 tier/cycle/sellerId 해석")
    void seller_meta_from_projection() {
        jdbc.update("""
                INSERT INTO settlement_payment_view
                    (payment_id, order_id, amount, status, seller_id, seller_tier, settlement_cycle,
                     payment_method, refunded_amount, updated_at)
                VALUES (7001, 6001, 1000.00, 'CAPTURED', 42, 'VIP', 'T3', 'CARD', 0.00, now())
                """);

        assertThat(sellerMetaAdapter.findSellerIdByPaymentId(7001L)).contains(42L);
        Optional<SellerTier> tier = sellerMetaAdapter.findTierByPaymentId(7001L);
        assertThat(tier).isPresent();
        Optional<SettlementCycle> cycle = sellerMetaAdapter.findCycleByPaymentId(7001L);
        assertThat(cycle).isPresent();

        // 미존재 payment → empty
        assertThat(sellerMetaAdapter.findSellerIdByPaymentId(9999L)).isEmpty();
    }
}
