package github.lms.lemuel.settlement.adapter.out.persistence;

import github.lms.lemuel.settlement.application.port.out.CountSettlementsInPeriodPort;
import github.lms.lemuel.settlement.domain.RateScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

/**
 * 소급 판정용 정산 건수 조회 (ADR 0032 결정 ⑤).
 *
 * <p>정산은 셀러를 직접 갖지 않고 프로젝션(settlement_payment_view)으로 해석하므로(ADR 0020),
 * SELLER scope 는 그 뷰를 경유해 센다. TIER scope 는 뷰의 seller_tier 로 판정한다.
 * order DB 직접 조회는 없다 — MSA 경계 유지.
 */
@Repository
public class SettlementPeriodCountJdbcAdapter implements CountSettlementsInPeriodPort {

    private final JdbcTemplate jdbcTemplate;

    public SettlementPeriodCountJdbcAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long countInPeriod(RateScope scope, String scopeKey, LocalDate from, LocalDate to) {
        String column = scope == RateScope.SELLER ? "v.seller_id::text" : "v.seller_tier";
        String sql = """
                SELECT COUNT(*)
                  FROM settlements s
                  JOIN settlement_payment_view v ON v.payment_id = s.payment_id
                 WHERE s.settlement_date >= ? AND s.settlement_date < ?
                   AND %s = ?
                """.formatted(column);
        Long count = jdbcTemplate.queryForObject(sql, Long.class, from, to, scopeKey);
        return count == null ? 0L : count;
    }
}
