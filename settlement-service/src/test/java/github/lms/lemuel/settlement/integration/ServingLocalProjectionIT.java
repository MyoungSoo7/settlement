package github.lms.lemuel.settlement.integration;

import github.lms.lemuel.SettlementServiceApplication;
import github.lms.lemuel.report.adapter.out.persistence.CashflowAggregateQueryAdapter;
import github.lms.lemuel.report.domain.BucketGranularity;
import github.lms.lemuel.report.domain.CashflowBucket;
import github.lms.lemuel.settlement.adapter.in.web.response.SettlementPageResponse;
import github.lms.lemuel.settlement.adapter.out.persistence.SettlementSearchJdbcRepository;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0020 Phase 5.5a — 서빙 경로 로컬화 런타임 검증.
 *
 * <p>{@link SettlementSearchJdbcRepository}(검색 API)와 {@link CashflowAggregateQueryAdapter}
 * (셀러별 리포트)가 <b>order 테이블 없이</b> settlement 소유 로컬 프로젝션(settlement_*_view)과
 * 자체 settlements 만으로 정확한 결과를 반환함을 입증한다 — opslab 직독 제거가 실제로 동작함.
 *
 * <p>{@link SettlementDbBootIT} 와 동일하게 격리된 settlement_db(Testcontainers)에 V1 베이스라인을
 * 적용해 부팅한다. order DB 는 존재하지 않는다.
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
class ServingLocalProjectionIT {

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

    private static final LocalDate D = LocalDate.of(2026, 6, 17);
    private static final long SELLER_ID = 500L;

    @Autowired JdbcTemplate jdbc;
    @Autowired SettlementSearchJdbcRepository searchRepository;
    @Autowired CashflowAggregateQueryAdapter cashflowAdapter;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM settlement_adjustments");
        jdbc.update("DELETE FROM settlements");
        jdbc.update("DELETE FROM settlement_payment_view");
        jdbc.update("DELETE FROM settlement_order_view");
        jdbc.update("DELETE FROM settlement_user_view");
        jdbc.update("DELETE FROM settlement_product_view");

        // order 도메인 데이터는 전부 settlement 소유 로컬 프로젝션으로만 존재 (opslab 없음)
        jdbc.update("INSERT INTO settlement_user_view (user_id, email, updated_at) VALUES (100, 'kim@test.com', now())");
        jdbc.update("INSERT INTO settlement_product_view (product_id, name, updated_at) VALUES (200, 'Tile A', now())");
        jdbc.update("""
                INSERT INTO settlement_order_view (order_id, user_id, product_id, status, amount, created_at, updated_at)
                VALUES (10, 100, 200, 'PAID', 1000.00, now(), now())
                """);
        jdbc.update("""
                INSERT INTO settlement_payment_view (payment_id, order_id, amount, status, seller_id, refunded_amount, updated_at)
                VALUES (1, 10, 1000.00, 'CAPTURED', ?, 0.00, now())
                """, SELLER_ID);
        jdbc.update("""
                INSERT INTO settlements
                  (id, payment_id, order_id, payment_amount, refunded_amount, commission, commission_rate,
                   net_amount, holdback_amount, holdback_rate, holdback_released, settlement_date, status,
                   version, created_at, updated_at)
                VALUES (1, 1, 10, 1000.00, 0.00, 35.00, 0.0350, 965.00, 0.00, 0.0000, false, ?, 'DONE', 0, now(), now())
                """, java.sql.Date.valueOf(D));
    }

    @Test
    @DisplayName("검색 API: order 테이블 없이 로컬 프로젝션 조인으로 주문자명·상품명을 반환한다")
    void search_resolvesOrdererAndProductFromLocalProjections() {
        SettlementPageResponse res = searchRepository.search(
                null, null, null, null, null, null, 0, 20, "createdAt", "DESC");

        assertThat(res.getTotalElements()).isEqualTo(1);
        assertThat(res.getSettlements()).hasSize(1);
        assertThat(res.getSettlements().get(0).getOrdererName()).isEqualTo("kim@test.com");
        assertThat(res.getSettlements().get(0).getProductName()).isEqualTo("Tile A");
        assertThat(res.getSettlements().get(0).getFinalAmount()).isEqualByComparingTo("965.00");
    }

    @Test
    @DisplayName("검색 API: 주문자명·상품명 필터가 로컬 프로젝션 컬럼으로 동작한다")
    void search_filtersByProjectionColumns() {
        assertThat(searchRepository.search("kim", null, null, null, null, null, 0, 20, "createdAt", "DESC")
                .getTotalElements()).isEqualTo(1);
        assertThat(searchRepository.search(null, "Tile", null, null, null, null, 0, 20, "createdAt", "DESC")
                .getTotalElements()).isEqualTo(1);
        assertThat(searchRepository.search(null, "없는상품", null, null, null, null, 0, 20, "createdAt", "DESC")
                .getTotalElements()).isZero();
    }

    @Test
    @DisplayName("셀러별 캐시플로우: products 조인 없이 settlement_payment_view.seller_id 로 집계한다")
    void cashflowBySeller_usesPaymentViewSellerId() {
        List<CashflowBucket> buckets = cashflowAdapter.aggregateBySeller(D, D, BucketGranularity.DAY, SELLER_ID);

        assertThat(buckets).hasSize(1);
        assertThat(buckets.get(0).gmv()).isEqualByComparingTo("1000.00");
        assertThat(buckets.get(0).netSettlement()).isEqualByComparingTo("965.00");

        // 다른 셀러로는 잡히지 않음 (필터가 실제로 동작)
        assertThat(cashflowAdapter.aggregateBySeller(D, D, BucketGranularity.DAY, 999L)).isEmpty();
    }
}
