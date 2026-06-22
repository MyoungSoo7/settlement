package github.lms.lemuel.report.adapter.out.persistence;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.DateTemplate;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import github.lms.lemuel.report.application.port.out.LoadCashflowAggregatePort;
import github.lms.lemuel.report.domain.BucketGranularity;
import github.lms.lemuel.report.domain.CashflowBucket;
import github.lms.lemuel.settlement.adapter.out.persistence.QSettlementJpaEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * settlements 테이블을 day/week/month 단위로 집계하는 어댑터.
 * date_trunc(unit, settlement_date) 로 버킷팅 후 합계 컬럼을 투영한다.
 *
 * <p>주의: report 도메인은 settlement 엔티티(Q클래스)를 읽기만 한다.
 * settlement 도메인 서비스·유스케이스에 의존하지 않으므로 경계는 유지된다.
 */
@Repository
@RequiredArgsConstructor
public class CashflowAggregateQueryAdapter implements LoadCashflowAggregatePort {

    private static final QSettlementJpaEntity settlement = QSettlementJpaEntity.settlementJpaEntity;

    private final JPAQueryFactory queryFactory;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<CashflowBucket> aggregate(LocalDate from, LocalDate to, BucketGranularity granularity) {
        DateTemplate<LocalDate> bucketExpr = bucketExpr(granularity);

        return queryFactory
                .select(Projections.constructor(CashflowBucket.class,
                        bucketExpr,
                        settlement.count(),
                        settlement.paymentAmount.sum().coalesce(BigDecimal.ZERO),
                        settlement.refundedAmount.sum().coalesce(BigDecimal.ZERO),
                        settlement.commission.sum().coalesce(BigDecimal.ZERO),
                        settlement.netAmount.sum().coalesce(BigDecimal.ZERO)
                ))
                .from(settlement)
                .where(
                        settlement.settlementDate.goe(from),
                        settlement.settlementDate.loe(to)
                )
                .groupBy(bucketExpr)
                .orderBy(bucketExpr.asc())
                .fetch();
    }

    @Override
    public List<CashflowBucket> aggregateBySeller(LocalDate from, LocalDate to,
                                                  BucketGranularity granularity, Long sellerId) {
        if (sellerId == null) {
            throw new IllegalArgumentException("sellerId is required");
        }
        // ADR 0020 Phase 5.5 — 서빙 경로 로컬화: order 의 products.seller_id 를 조인하는 대신
        // settlement 소유 프로젝션 settlement_payment_view.seller_id 를 payment_id 로 조인해 필터한다.
        // settlement_db 단독으로 셀러별 집계가 성립 (order DB 의존 0).
        String bucketExpr = switch (granularity) {
            case DAY -> "s.settlement_date";
            case WEEK -> "CAST(date_trunc('week', s.settlement_date) AS date)";
            case MONTH -> "CAST(date_trunc('month', s.settlement_date) AS date)";
        };

        String sql = String.format("""
                SELECT %s AS bucket,
                       COUNT(*) AS cnt,
                       COALESCE(SUM(s.payment_amount), 0) AS gmv,
                       COALESCE(SUM(s.refunded_amount), 0) AS refunded,
                       COALESCE(SUM(s.commission), 0) AS commission,
                       COALESCE(SUM(s.net_amount), 0) AS net
                FROM settlements s
                JOIN settlement_payment_view pv ON pv.payment_id = s.payment_id
                WHERE s.settlement_date BETWEEN ? AND ?
                  AND pv.seller_id = ?
                GROUP BY %s
                ORDER BY %s ASC
                """, bucketExpr, bucketExpr, bucketExpr);

        return jdbcTemplate.query(sql, (rs, rowNum) -> new CashflowBucket(
                rs.getObject("bucket", LocalDate.class),
                rs.getLong("cnt"),
                rs.getBigDecimal("gmv"),
                rs.getBigDecimal("refunded"),
                rs.getBigDecimal("commission"),
                rs.getBigDecimal("net")
        ), from, to, sellerId);
    }

    private DateTemplate<LocalDate> bucketExpr(BucketGranularity granularity) {
        return switch (granularity) {
            case DAY -> Expressions.dateTemplate(LocalDate.class, "{0}", settlement.settlementDate);
            case WEEK -> Expressions.dateTemplate(LocalDate.class,
                    "CAST(date_trunc('week', {0}) AS date)", settlement.settlementDate);
            case MONTH -> Expressions.dateTemplate(LocalDate.class,
                    "CAST(date_trunc('month', {0}) AS date)", settlement.settlementDate);
        };
    }
}
