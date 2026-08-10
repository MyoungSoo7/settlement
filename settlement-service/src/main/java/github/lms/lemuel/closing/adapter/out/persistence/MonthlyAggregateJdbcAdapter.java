package github.lms.lemuel.closing.adapter.out.persistence;

import github.lms.lemuel.closing.application.dto.MonthlyAggregateSnapshot;
import github.lms.lemuel.closing.application.dto.SellerAggregateRow;
import github.lms.lemuel.closing.application.port.out.LoadLedgerClosedPort;
import github.lms.lemuel.closing.application.port.out.LoadMonthlyAggregatePort;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * 월 집계 JDBC 어댑터 — 정보계 마감의 ETL 읽기 축.
 *
 * <p>settlements(원천) × settlement_payment_view(셀러 매핑 프로젝션)를 settlement_db 안에서만
 * 조인한다(ADR 0020 — cross-DB 0). 기준일은 {@code settlement_date}, 확정 실적은 DONE 만.
 *
 * <p>ledger_periods 는 ledger 모듈 코드가 아니라 테이블을 직접 읽는다 —
 * 모듈 간 코드 결합 대신 DB 레벨 공유(report 모듈과 같은 방식).
 */
@Repository
@RequiredArgsConstructor
public class MonthlyAggregateJdbcAdapter implements LoadMonthlyAggregatePort, LoadLedgerClosedPort {

    private static final String AGGREGATE_SQL = """
            SELECT v.seller_id,
                   COUNT(*)               AS settlement_count,
                   SUM(s.payment_amount)  AS gross_amount,
                   SUM(s.refunded_amount) AS refunded_amount,
                   SUM(s.commission)      AS commission_amount,
                   SUM(s.holdback_amount) AS holdback_amount,
                   SUM(s.net_amount)      AS net_amount
            FROM settlements s
            JOIN settlement_payment_view v ON v.payment_id = s.payment_id AND v.seller_id IS NOT NULL
            WHERE s.status = 'DONE'
              AND s.settlement_date >= ? AND s.settlement_date < ?
            GROUP BY v.seller_id
            ORDER BY v.seller_id
            """;

    /** 셀러 매핑 실패(프로젝션 lag·누락)로 마트에서 빠지는 DONE 정산 — 0 이 정상. */
    private static final String UNMAPPED_SQL = """
            SELECT COUNT(*)
            FROM settlements s
            LEFT JOIN settlement_payment_view v ON v.payment_id = s.payment_id
            WHERE s.status = 'DONE'
              AND s.settlement_date >= ? AND s.settlement_date < ?
              AND (v.payment_id IS NULL OR v.seller_id IS NULL)
            """;

    /** 아직 미확정(REQUESTED/PROCESSING) — 마감 후 유입될 수 있는 양의 감시 지표. */
    private static final String PENDING_SQL = """
            SELECT COUNT(*)
            FROM settlements s
            WHERE s.status IN ('REQUESTED', 'PROCESSING')
              AND s.settlement_date >= ? AND s.settlement_date < ?
            """;

    private static final String LEDGER_CLOSED_SQL = """
            SELECT EXISTS(SELECT 1 FROM ledger_periods WHERE period_ym = ? AND status = 'CLOSED')
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public MonthlyAggregateSnapshot load(YearMonth period) {
        LocalDate from = period.atDay(1);
        LocalDate toExclusive = period.plusMonths(1).atDay(1);

        List<SellerAggregateRow> rows = jdbcTemplate.query(AGGREGATE_SQL,
                (rs, i) -> new SellerAggregateRow(
                        rs.getLong("seller_id"),
                        rs.getLong("settlement_count"),
                        rs.getBigDecimal("gross_amount"),
                        rs.getBigDecimal("refunded_amount"),
                        rs.getBigDecimal("commission_amount"),
                        rs.getBigDecimal("holdback_amount"),
                        rs.getBigDecimal("net_amount")),
                from, toExclusive);

        Long unmapped = jdbcTemplate.queryForObject(UNMAPPED_SQL, Long.class, from, toExclusive);
        Long pending = jdbcTemplate.queryForObject(PENDING_SQL, Long.class, from, toExclusive);

        return new MonthlyAggregateSnapshot(rows,
                unmapped != null ? unmapped : 0L,
                pending != null ? pending : 0L);
    }

    @Override
    public boolean isLedgerClosed(YearMonth period) {
        Boolean closed = jdbcTemplate.queryForObject(LEDGER_CLOSED_SQL, Boolean.class, period.toString());
        return Boolean.TRUE.equals(closed);
    }
}
