package github.lms.lemuel.settlement.adapter.out.persistence;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.DateTemplate;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import github.lms.lemuel.report.application.port.out.LoadCashflowAggregatePort;
import github.lms.lemuel.report.domain.BucketGranularity;
import github.lms.lemuel.report.domain.CashflowBucket;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * report 가 선언한 {@link LoadCashflowAggregatePort} 를 settlement 슬라이스가 구현한다 —
 * settlements 테이블을 day/week/month 단위로 집계해 {@link CashflowBucket} 으로 넘긴다
 * (date_trunc(unit, settlement_date) 버킷팅 후 합계 컬럼 투영).
 *
 * <p>이 클래스가 <b>settlement 쪽에 사는 이유</b>: 이전에는 report 의 어댑터가 settlement 의 Q클래스를
 * 직접 읽었다. "읽기만 하니 경계는 유지된다"는 종전 주석은 절반만 맞다 — 읽기여도 settlement 의
 * <b>저장 스키마(컬럼 이름·타입)가 report 의 컴파일 의존</b>이 되어, settlement 쪽 컬럼 변경이
 * report 를 깨뜨린다. 집계 SQL 은 데이터를 소유한 슬라이스가 제공하고, report 는 자기가 선언한
 * 포트만 안다.
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
    // 동적 SQL 경고(java:S2077) 억제 — 조립되는 건 enum(BucketGranularity)이 고르는 버킷 표현식
    // 3종 중 하나(코드 상수)뿐이다. 기간·셀러는 바인딩 파라미터(?)로 넘긴다.
    @SuppressWarnings("java:S2077")
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
