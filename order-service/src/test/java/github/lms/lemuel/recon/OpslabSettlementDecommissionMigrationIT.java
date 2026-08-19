package github.lms.lemuel.recon;

import github.lms.lemuel.common.outbox.adapter.out.persistence.OutboxSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0020 — fresh 부트스트랩에서 opslab 정산계 좀비가 <b>재생성되지 않음</b>을 마이그레이션 레벨에서 못박는다.
 *
 * <p>{@link OpslabDecommissionIT} 는 합성 테이블로 "DROP 이 order 테이블을 해치지 않는다"는 <i>원리</i>만
 * 입증한다. 그건 운영 DB 에 {@code scripts/etl/settlement-opslab-decommission.sh} 를 한 번 돌리는 경로를
 * 지켜줄 뿐, 새 환경을 부트스트랩하면 order Flyway 가 정산계 테이블을 다시 만들고 {@code V17__seed_data.sql}
 * 이 거기에 정산 1,000행을 다시 채운다. 스크립트 말미의 "신규 부트스트랩 시 <b>빈</b> 테이블이 재생성되나
 * 무해"라는 가정이 시드 때문에 성립하지 않는다 — 좀비는 지워도 부활한다.
 *
 * <p>그래서 여기서는 <b>실제 order Flyway 전량을 Postgres 에 적용한 뒤</b> 최종 스키마를 본다. 정산계
 * 테이블이 하나도 남지 않아야 하고, order 가 계속 쓰는 테이블은 데이터까지 살아 있어야 한다.
 * 이 테스트가 GREEN 인 한 누가 정산계 테이블을 만드는 마이그레이션을 새로 넣어도 즉시 잡힌다.
 */
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
@DataJpaTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(OutboxSchema.class)
@ActiveProfiles("test")
class OpslabSettlementDecommissionMigrationIT {

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

    /**
     * 정산 정본은 settlement_db 다(ADR 0020). opslab 에 같은 이름으로 남은 것은 모놀리스 잔재이며
     * order 코드의 실제 접근은 0건이다 — {@code scripts/etl/settlement-opslab-decommission.sh} 의
     * DROP_TABLES 와 동일 목록(미러).
     */
    private static final List<String> ZOMBIE_TABLES = List.of(
            "settlement_adjustments", "settlement_loan_deductions", "pg_reconciliation_discrepancies",
            "pg_reconciliation_runs", "ledger_outbox", "ledger_entries", "chargebacks", "payouts",
            "settlement_index_queue", "settlement_schedule_config", "settlement_payment_view",
            "settlement_order_view", "settlement_user_view", "settlement_product_view", "settlements");

    /** order 가 계속 사용 — 드롭돼도 안 되고, 시드 데이터가 사라져도 안 된다(공유 outbox_events 포함). */
    private static final List<String> LIVE_TABLES = List.of(
            "orders", "payments", "users", "products", "refunds", "outbox_events");

    @Autowired DataSource dataSource;

    @Test
    @DisplayName("Flyway 전량 적용 후 opslab 에 정산계 좀비 테이블이 하나도 없다")
    void migrations_leaveNoLegacySettlementTables() throws Exception {
        List<String> survivors = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            for (String t : ZOMBIE_TABLES) {
                if (tableExists(c, t)) {
                    survivors.add(t + "(rows=" + rowCount(c, t) + ")");
                }
            }
        }
        assertThat(survivors)
                .as("opslab 정산계 좀비 테이블은 마이그레이션 종료 시점에 남아 있으면 안 된다 "
                        + "— 남아 있으면 fresh 부트스트랩마다 V17 시드가 좀비 정산을 되살린다")
                .isEmpty();
    }

    @Test
    @DisplayName("좀비 제거가 order 라이브 테이블과 그 시드 데이터를 건드리지 않는다")
    void decommission_preservesOrderTablesAndSeedRows() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            for (String t : LIVE_TABLES) {
                assertThat(tableExists(c, t)).as("order 라이브 테이블 %s 는 보존돼야 한다", t).isTrue();
            }
            // V17 시드는 주문·결제를 각 1,000건 만든다. CASCADE 가 부모 방향으로 번지면 여기서 무너진다.
            assertThat(rowCount(c, "orders")).as("V17 시드 주문 보존").isGreaterThanOrEqualTo(1000L);
            assertThat(rowCount(c, "payments")).as("V17 시드 결제 보존").isGreaterThanOrEqualTo(1000L);
        }
    }

    private boolean tableExists(Connection c, String table) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            ps.setString(1, "opslab." + table);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    /** 테이블명은 위 상수 목록에서만 오므로 식별자 연결이 안전하다(사용자 입력 경로 없음). */
    private long rowCount(Connection c, String table) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM opslab." + table);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
