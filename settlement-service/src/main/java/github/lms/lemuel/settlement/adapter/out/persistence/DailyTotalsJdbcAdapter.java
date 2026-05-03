package github.lms.lemuel.settlement.adapter.out.persistence;

import github.lms.lemuel.settlement.application.port.out.LoadDailyTotalsPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 일일 대사용 cross-domain aggregation 어댑터.
 *
 * payments / refunds / settlements 세 테이블을 가로질러 집계하는 CQRS 전용 read model.
 * JPA 엔티티로 재조립하지 않고 JDBC SUM 으로 직행 — ArchUnit 규칙 관점에서는
 * settlement.adapter 가 다른 도메인의 테이블명을 SQL 로 참조하는 형태이지만,
 * 이는 감사(audit) 도구의 본질상 모든 원장을 들여다봐야 하므로 허용.
 */
@Repository
public class DailyTotalsJdbcAdapter implements LoadDailyTotalsPort {

    private final JdbcTemplate jdbcTemplate;

    public DailyTotalsJdbcAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public BigDecimal sumCapturedPayments(LocalDate date) {
        String sql = """
            SELECT COALESCE(SUM(amount), 0)
            FROM opslab.payments
            WHERE status = 'CAPTURED'
              AND captured_at::date = ?
            """;
        return queryDecimal(sql, date);
    }

    @Override
    public BigDecimal sumCompletedRefunds(LocalDate date) {
        String sql = """
            SELECT COALESCE(SUM(amount), 0)
            FROM opslab.refunds
            WHERE status = 'COMPLETED'
              AND completed_at::date = ?
            """;
        return queryDecimal(sql, date);
    }

    @Override
    public BigDecimal sumSettlementNet(LocalDate date) {
        String sql = """
            SELECT COALESCE(SUM(net_amount), 0)
            FROM opslab.settlements
            WHERE settlement_date = ?
              AND status <> 'CANCELED'
            """;
        return queryDecimal(sql, date);
    }

    @Override
    public BigDecimal sumSettlementCommission(LocalDate date) {
        String sql = """
            SELECT COALESCE(SUM(commission), 0)
            FROM opslab.settlements
            WHERE settlement_date = ?
              AND status <> 'CANCELED'
            """;
        return queryDecimal(sql, date);
    }

    private BigDecimal queryDecimal(String sql, LocalDate date) {
        BigDecimal result = jdbcTemplate.queryForObject(sql, BigDecimal.class, date);
        return result != null ? result : BigDecimal.ZERO;
    }
}
