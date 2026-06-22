package github.lms.lemuel.recon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0020 Phase 5.5 — opslab decommission 검증 (settlement 죽은 복사본 제거의 안전성).
 *
 * <p>order Flyway 는 settlement 잔여 테이블을 {@code opslab} 스키마에 만든다(default-schema=opslab).
 * {@code scripts/etl/settlement-opslab-decommission.sh} 가 그 스키마에서 settlement 테이블만
 * 정확히 제거하고, order 가 계속 쓰는 테이블(orders/payments/...)·공유 테이블(outbox_events)은
 * 데이터까지 보존함을 격리 Postgres(Testcontainers)에서 입증한다 — 실데이터 무손상.
 *
 * <p>특히 settlement→order FK 가 있어도 {@code DROP ... CASCADE} 가 order 테이블을 건드리지 않음을
 * 확인한다(CASCADE 는 드롭 대상에 의존하는 객체만 정리하므로 부모 방향은 안전).
 */
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class OpslabDecommissionIT {

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> OPSLAB = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("opslab").withUsername("test").withPassword("test");

    /** scripts/etl/settlement-opslab-decommission.sh 의 DROP_TABLES 와 동일(미러). */
    private static final List<String> SETTLEMENT_TABLES = List.of(
            "settlement_adjustments", "settlement_loan_deductions", "pg_reconciliation_discrepancies",
            "pg_reconciliation_runs", "ledger_outbox", "ledger_entries", "chargebacks", "payouts",
            "settlement_index_queue", "settlement_payment_view", "settlement_order_view",
            "settlement_user_view", "settlement_product_view", "settlements");

    /** order 가 계속 사용 — 절대 드롭되면 안 됨(공유 outbox_events 포함). */
    private static final List<String> ORDER_TABLES = List.of(
            "orders", "payments", "users", "products", "refunds", "outbox_events");

    @Test
    void decommission_dropsSettlementTablesInOpslabSchema_preservesOrderTables() throws Exception {
        try (Connection c = DriverManager.getConnection(
                OPSLAB.getJdbcUrl(), OPSLAB.getUsername(), OPSLAB.getPassword());
             Statement st = c.createStatement()) {

            // order Flyway 와 동일하게 opslab 스키마에 테이블 구성
            st.execute("CREATE SCHEMA IF NOT EXISTS opslab");
            for (String t : ORDER_TABLES) {
                st.execute("CREATE TABLE opslab." + t + " (id bigint PRIMARY KEY)");
                st.execute("INSERT INTO opslab." + t + " (id) VALUES (1)");
            }
            // settlement 죽은 복사본 — order 를 FK 로 참조(공유 시절 잔재 가정)
            for (String t : SETTLEMENT_TABLES) {
                st.execute("CREATE TABLE opslab." + t
                        + " (id bigint PRIMARY KEY, order_id bigint REFERENCES opslab.orders(id))");
                st.execute("INSERT INTO opslab." + t + " (id, order_id) VALUES (1, 1)");
            }

            // ── 디커미션 실행 (스크립트와 동일: opslab 스키마, CASCADE, 단일 트랜잭션) ──
            c.setAutoCommit(false);
            for (String t : SETTLEMENT_TABLES) {
                st.execute("DROP TABLE IF EXISTS opslab." + t + " CASCADE");
            }
            c.commit();
            c.setAutoCommit(true);

            // settlement 죽은 테이블은 전부 사라짐
            for (String t : SETTLEMENT_TABLES) {
                assertThat(exists(c, t)).as("settlement 테이블 %s 는 드롭돼야 함", t).isFalse();
            }
            // order 라이브·공유 테이블은 데이터까지 보존
            for (String t : ORDER_TABLES) {
                assertThat(exists(c, t)).as("order 테이블 %s 는 보존돼야 함", t).isTrue();
                assertThat(rowCount(c, t)).as("order 테이블 %s 행 보존", t).isEqualTo(1L);
            }
        }
    }

    private boolean exists(Connection c, String table) throws Exception {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT to_regclass('opslab." + table + "') IS NOT NULL")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    private long rowCount(Connection c, String table) throws Exception {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM opslab." + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
