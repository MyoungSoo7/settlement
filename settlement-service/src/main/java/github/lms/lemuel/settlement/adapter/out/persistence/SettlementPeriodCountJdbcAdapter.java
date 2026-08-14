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
    // 동적 SQL 경고(java:S2077) 억제 — 조립되는 건 enum(RateScope)이 고르는 두 컬럼명 중 하나뿐이다.
    // 기간·scopeKey 등 값은 모두 바인딩 파라미터(?)다. 컬럼명은 바인딩할 수 없어 분기로 고정한다.
    @SuppressWarnings("java:S2077")
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
