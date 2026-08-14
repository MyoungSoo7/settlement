package github.lms.lemuel.settlement.integration;

import github.lms.lemuel.SettlementServiceApplication;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 핫패스 쿼리 인덱스 계약 테스트 — settlement_db(public), 실 Flyway 체인.
 *
 * <p>settlement_db V1 베이스라인은 Hibernate DDL export 로 만들어져, opslab(order-service)이
 * 누적해 온 성능 인덱스가 통째로 유실된 채 출발했다(리포지토리 Javadoc 은 인덱스를 "활용한다"고
 * 주장하는데 실제 DB 에는 없던 상태). 이 IT 는 리포지토리 쿼리 형태와 1:1 로 대응하는 인덱스가
 * 실제 Flyway 체인 적용 결과에 존재하는지 {@code pg_indexes} 로 대조해, 베이스라인 재생성·프루닝
 * 실수로 같은 유실이 재발하면 빌드 시점에 깨지게 한다.
 *
 * <p>대조 대상 쿼리 경로(각 인덱스의 존재 이유)는 V20260807120000__query_path_index_restore.sql
 * 주석이 정본이다. 프루닝된 인덱스(idx_settlements_settlement_date)는 부재까지 검증한다 —
 * 상위 복합 인덱스로 대체됐는데 단일컬럼이 부활하면 그것도 드리프트다.
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
class QueryPathIndexContractIT {

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

    @Autowired JdbcTemplate jdbc;

    private String indexDef(String table, String indexName) {
        return jdbc.query(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = ? AND indexname = ?",
                (rs, i) -> rs.getString(1), table, indexName).stream().findFirst().orElse(null);
    }

    @Test
    @DisplayName("settlements: 커서 페이징·커버링·승인·생성일 인덱스가 존재한다 (Javadoc 이 주장하는 그 인덱스)")
    void settlementsQueryPathIndexesExist() {
        // SettlementQueryRepositoryImpl.searchSettlements — (settlement_date, id) 복합 커서
        assertThat(indexDef("settlements", "idx_settlements_date_id_desc"))
                .as("커서 페이징 인덱스").isNotNull()
                .contains("settlement_date DESC", "id DESC");

        // findDailySummary/findMonthlySummary/getPaymentRefundAggregation — Index-Only Scan 커버링
        assertThat(indexDef("settlements", "idx_settlements_date_status_amounts"))
                .as("일별/월별 요약 커버링 인덱스").isNotNull()
                .contains("settlement_date", "status")
                .contains("INCLUDE")
                .contains("payment_amount", "refunded_amount", "commission", "net_amount");

        // findByApprovalStatus — 승인 워크플로 partial (status IN 3종, ORDER BY id DESC)
        assertThat(indexDef("settlements", "idx_settlements_approval_id"))
                .as("승인 상태 추적 partial 인덱스").isNotNull()
                .contains("WHERE").contains("WAITING_APPROVAL", "APPROVED", "REJECTED");

        // DailyTotalsJdbcAdapter(일일 대사)·PeriodRecon countSettlementsCreated — created_at 범위
        assertThat(indexDef("settlements", "idx_settlements_created_at"))
                .as("생성일 기준 일일 대사 인덱스").isNotNull()
                .contains("created_at");
    }

    @Test
    @DisplayName("settlements: 프루닝된 단일컬럼 settlement_date 인덱스는 부활하지 않는다")
    void prunedSingleColumnDateIndexStaysAbsent() {
        assertThat(indexDef("settlements", "idx_settlements_settlement_date"))
                .as("상위 복합(date_id_desc/date_status_amounts) 선두열에 완전 포섭 — 부활 금지")
                .isNull();
    }

    @Test
    @DisplayName("payouts: 셀러별·전체 완료 집계 partial 인덱스가 존재한다 (order V43/V20260611 동형)")
    void payoutCompletedAggregationIndexesExist() {
        // sumCompletedBySellerBetween — seller_id + completed_at 범위, COMPLETED 만
        assertThat(indexDef("payouts", "idx_payouts_seller_date"))
                .as("셀러별 완료 지급 집계").isNotNull()
                .contains("seller_id", "completed_at").contains("COMPLETED");

        // sumCompletedBetween — 기간 전체 완료 집계
        assertThat(indexDef("payouts", "idx_payouts_completed_at"))
                .as("기간 완료 지급 집계").isNotNull()
                .contains("completed_at").contains("COMPLETED");
    }

    @Test
    @DisplayName("ledger_entries: 시산표 (settlement_date, 계정) 복합 인덱스가 존재한다 (order 프루닝팩 동형)")
    void trialBalanceCompositeIndexesExist() {
        assertThat(indexDef("ledger_entries", "idx_ledger_date_debit"))
                .as("차변 시산표").isNotNull()
                .contains("settlement_date", "debit_account");
        assertThat(indexDef("ledger_entries", "idx_ledger_date_credit"))
                .as("대변 시산표").isNotNull()
                .contains("settlement_date", "credit_account");
    }

    @Test
    @DisplayName("settlement_adjustments/settlement_payment_view: 조정일·셀러 조회 인덱스가 존재한다")
    void adjustmentAndProjectionIndexesExist() {
        // PeriodRecon sumAdjustmentsAbsolute·Integrity missingReverse — adjustment_date 스코프
        assertThat(indexDef("settlement_adjustments", "idx_adjustments_adjustment_date"))
                .as("조정일 스코프").isNotNull()
                .contains("adjustment_date");

        // CashflowAggregateQueryAdapter.aggregateBySeller — 셀러 필터 + payment_id 조인 피드
        assertThat(indexDef("settlement_payment_view", "idx_settlement_payment_view_seller"))
                .as("셀러별 캐시플로 조인").isNotNull()
                .contains("seller_id", "payment_id");
    }
}
