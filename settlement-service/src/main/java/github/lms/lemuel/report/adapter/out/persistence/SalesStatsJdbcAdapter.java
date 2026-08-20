package github.lms.lemuel.report.adapter.out.persistence;

import github.lms.lemuel.report.application.port.out.LoadSalesStatsPort;
import github.lms.lemuel.report.domain.CashflowBucket;
import github.lms.lemuel.report.domain.CashflowTotals;
import github.lms.lemuel.report.domain.ReportPeriod;
import github.lms.lemuel.report.domain.SalesDimension;
import github.lms.lemuel.report.domain.SalesSlice;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 매출 통계 집계 어댑터 — {@code settlements} + settlement 소유 프로젝션만 읽는다.
 *
 * <p><b>MSA 경계(ADR 0020)</b>: 결제수단·셀러·상품명은 order 원천이 아니라 로컬 프로젝션
 * ({@code settlement_payment_view} / {@code settlement_order_view} / {@code settlement_product_view} /
 * {@code settlement_user_view})에서 읽는다. settlement_db 단독으로 대시보드가 성립한다.
 *
 * <p><b>왜 전부 LEFT JOIN 인가</b>: 프로젝션은 이벤트로 따라오므로 잠깐 비어 있을 수 있다.
 * INNER JOIN 이면 그 사이의 정산이 집계에서 <b>조용히 사라져</b> 합계가 {@code /api/reports/cashflow}
 * 와 어긋난다. LEFT JOIN 이면 라벨만 비고(→ {@code UNKNOWN}) 금액은 남는다 — 없는 척하는 것보다
 * 모른다고 말하는 편이 정직하다.
 *
 * <p><b>합계는 프로젝션을 아예 타지 않는다</b>: {@link #totals} 는 {@code settlements} 단독 집계라
 * 프로젝션 지연과 무관하게 항상 캐시플로우 리포트와 같은 값을 낸다.
 *
 * <p>날짜 축은 {@code settlement_date} 다 — 기존 캐시플로우 리포트와 같은 축이라야 두 화면이
 * 같은 숫자를 말한다.
 */
@Repository
@RequiredArgsConstructor
public class SalesStatsJdbcAdapter implements LoadSalesStatsPort {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public CashflowTotals totals(ReportPeriod period) {
        // 기간 전체를 버킷 하나로 집계한 뒤 이미 검증된 도메인 합산기에 넘긴다 —
        // 환불율 계산을 여기서 다시 쓰면 같은 규칙이 두 벌이 된다.
        CashflowBucket whole = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) AS cnt,
                       COALESCE(SUM(s.payment_amount), 0)  AS gmv,
                       COALESCE(SUM(s.refunded_amount), 0) AS refunded,
                       COALESCE(SUM(s.commission), 0)      AS commission,
                       COALESCE(SUM(s.net_amount), 0)      AS net
                FROM settlements s
                WHERE s.settlement_date >= ? AND s.settlement_date < ?
                """,
                (rs, rowNum) -> new CashflowBucket(
                        period.from(),
                        rs.getLong("cnt"),
                        rs.getBigDecimal("gmv"),
                        rs.getBigDecimal("refunded"),
                        rs.getBigDecimal("commission"),
                        rs.getBigDecimal("net")),
                period.from(), period.endExclusive());

        // null 분기를 두지 않는다 — GROUP BY 없는 집계라 행은 항상 정확히 하나이고, 매퍼는 null 을
        // 돌려주지 않는다. 실행될 수 없는 방어는 방어가 아니라 "여기서 null 이 나올 수 있다"는
        // 잘못된 신호이고, 그 분기가 합계 0 을 반환하면 장애를 정상 리포트로 위장한다.
        return CashflowTotals.from(List.of(whole));
    }

    // 동적 SQL 경고(java:S2077) 억제 — 조립되는 조각은 전부 enum(SalesDimension)이 고르는
    // 코드 상수다. 기간과 상한은 ? 바인딩으로만 들어간다.
    @Override
    @SuppressWarnings("java:S2077")
    public List<SalesSlice> slices(ReportPeriod period, SalesDimension dimension, int limit) {
        DimensionSql sql = DimensionSql.of(dimension);

        String query = """
                SELECT %s AS label,
                       COUNT(*) AS cnt,
                       COALESCE(SUM(s.payment_amount), 0)  AS gmv,
                       COALESCE(SUM(s.refunded_amount), 0) AS refunded,
                       COALESCE(SUM(s.commission), 0)      AS commission,
                       COALESCE(SUM(s.net_amount), 0)      AS net
                FROM settlements s
                LEFT JOIN settlement_payment_view pv ON pv.payment_id = s.payment_id
                %s
                WHERE s.settlement_date >= ? AND s.settlement_date < ?
                GROUP BY %s
                ORDER BY COALESCE(SUM(s.payment_amount), 0) DESC, %s
                LIMIT ?
                """.formatted(sql.labelExpr(), sql.joins(), sql.groupExpr(), sql.groupExpr());

        return jdbcTemplate.query(query, (rs, rowNum) -> new SalesSlice(
                        rs.getString("label"),
                        rs.getLong("cnt"),
                        rs.getBigDecimal("gmv"),
                        rs.getBigDecimal("refunded"),
                        rs.getBigDecimal("commission"),
                        rs.getBigDecimal("net")),
                period.from(), period.endExclusive(), limit);
    }

    /**
     * 축 하나가 필요로 하는 SQL 조각 — 라벨식 · 추가 조인 · 그룹식.
     *
     * <p>랭킹 축(셀러·상품)은 <b>식별자로 묶고 이름으로 보여 준다</b>. 이름으로 묶으면 동명이인
     * 상품이 한 줄로 합쳐지고, 식별자만 보여 주면 운영자가 누구인지 알 수 없다.
     * 이름이 없으면 {@code seller#12} 처럼 식별자를 노출한다 — 프로젝션이 아직 안 온 경우다.
     */
    private record DimensionSql(String labelExpr, String joins, String groupExpr) {

        private static final String NO_JOIN = "";
        private static final String ORDER_PRODUCT_JOIN = """
                LEFT JOIN settlement_order_view ov   ON ov.order_id = s.order_id
                LEFT JOIN settlement_product_view pr ON pr.product_id = ov.product_id
                """;
        private static final String SELLER_JOIN =
                "LEFT JOIN settlement_user_view uv ON uv.user_id = pv.seller_id";

        static DimensionSql of(SalesDimension dimension) {
            return switch (dimension) {
                case PAYMENT_METHOD -> new DimensionSql("pv.payment_method", NO_JOIN, "pv.payment_method");
                case SELLER_TIER -> new DimensionSql("pv.seller_tier", NO_JOIN, "pv.seller_tier");
                case SETTLEMENT_STATUS -> new DimensionSql("s.status", NO_JOIN, "s.status");
                case SELLER -> new DimensionSql(
                        "COALESCE(MAX(uv.email), 'seller#' || pv.seller_id)", SELLER_JOIN, "pv.seller_id");
                case PRODUCT -> new DimensionSql(
                        "COALESCE(MAX(pr.name), 'product#' || ov.product_id)", ORDER_PRODUCT_JOIN, "ov.product_id");
            };
        }
    }
}
