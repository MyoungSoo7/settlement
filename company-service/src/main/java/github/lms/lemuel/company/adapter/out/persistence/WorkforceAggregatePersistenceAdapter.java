package github.lms.lemuel.company.adapter.out.persistence;

import github.lms.lemuel.company.application.port.out.BuildWorkforceAggregatePort;
import github.lms.lemuel.company.domain.AggregateRowTally;
import github.lms.lemuel.company.domain.NpsContributionRate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * 월별 사전 집계 원자 교체. JPA per-entity 로는 수십만 행 집계를 감당할 수 없어 집합 연산 SQL 한 방으로
 * 처리한다(CSV 벌크 upsert 와 같은 컨벤션).
 *
 * <p><b>원자성</b>: 한 트랜잭션에서 BUILDING 표시 → 해당 월 집계·백분위 전량 삭제 → 재삽입 → COMPLETE.
 * 중간에 실패하면 트랜잭션째로 롤백되어 직전 COMPLETE 집계가 그대로 남는다 — 부분 갱신도, 정리해야 할
 * stale BUILDING 행도 생기지 않는다. 조회 경로는 COMPLETE 인 월만 읽으므로 교체 중 빈 결과를 보지 않는다.
 */
@Component
public class WorkforceAggregatePersistenceAdapter implements BuildWorkforceAggregatePort {

    /**
     * 적격 모집단 + 축·단계별 집단 키 전개. 적격 판정은 도메인
     * ({@code CompanyWorkforce.eligibleForComparison()})과 같은 규칙이다 — 가입자수 0 이나 고지금액 0 은
     * 추정연봉을 산출할 수 없어 두 지표 중 하나를 만들 수 없으므로 모집단에서 뺀다.
     *
     * <p>추정연봉 역산식도 도메인과 같다: (당월고지금액 × 12) / (가입자수 × 해당월 보험료율), 원 단위 HALF_UP.
     * PostgreSQL {@code round(numeric)} 은 0.5 를 0 에서 먼 쪽으로 올리므로 Java HALF_UP 과 일치한다.
     */
    private static final String ELIGIBLE_GROUPS = """
            WITH eligible AS (
                SELECT workplace_name,
                       COALESCE(biz_reg_no_prefix, '')                            AS biz_reg_no_prefix,
                       NULLIF(TRIM(industry_code), '')                            AS industry_code,
                       sido,
                       sigungu,
                       headcount::numeric                                         AS headcount,
                       ROUND(monthly_billed_amount * 12 / (headcount * ?::numeric), 0) AS est_salary
                FROM company_workforce
                WHERE snapshot_month = ? AND headcount > 0 AND monthly_billed_amount > 0
            ),
            grouped AS (
                SELECT 'INDUSTRY' AS axis, 'EXACT' AS level, industry_code AS group_key,
                       workplace_name, biz_reg_no_prefix, headcount, est_salary
                FROM eligible WHERE industry_code IS NOT NULL
                UNION ALL
                SELECT 'INDUSTRY', 'BROADENED', LEFT(industry_code, 3),
                       workplace_name, biz_reg_no_prefix, headcount, est_salary
                FROM eligible WHERE industry_code IS NOT NULL
                UNION ALL
                SELECT 'REGION', 'EXACT', sido || ' ' || sigungu,
                       workplace_name, biz_reg_no_prefix, headcount, est_salary
                FROM eligible WHERE sido IS NOT NULL AND sigungu IS NOT NULL
                UNION ALL
                SELECT 'REGION', 'BROADENED', sido,
                       workplace_name, biz_reg_no_prefix, headcount, est_salary
                FROM eligible WHERE sido IS NOT NULL
            )
            """;

    /** 집단 중앙값은 NUMERIC 행 순위로 구한다. 짝수 표본은 가운데 두 값을 평균낸다. */
    private static final String INSERT_AGGREGATE = ELIGIBLE_GROUPS + """
            , metric_values(axis, level, group_key, metric, metric_value) AS (
                SELECT axis, level, group_key, 'HEADCOUNT', headcount FROM grouped
                UNION ALL
                SELECT axis, level, group_key, 'ESTIMATED_ANNUAL_SALARY', est_salary FROM grouped
            ),
            ranked AS (
                SELECT axis, level, group_key, metric, metric_value,
                       ROW_NUMBER() OVER (PARTITION BY axis, level, group_key, metric ORDER BY metric_value) AS row_number,
                       COUNT(*) OVER (PARTITION BY axis, level, group_key, metric) AS group_count
                FROM metric_values
            )
            INSERT INTO workforce_aggregate
                (snapshot_month, axis, level, group_key, metric, median, sample_size)
            SELECT ?, axis, level, group_key, metric,
                   ROUND(AVG(metric_value), 2), MAX(group_count)
            FROM ranked
            WHERE row_number IN ((group_count + 1) / 2, (group_count + 2) / 2)
            GROUP BY axis, level, group_key, metric
            """;

    /**
     * 사업장별 백분위 = cume_dist("이 값 이하인 레코드 비율") × 100. 동률 사업장은 같은 값을 갖는다.
     * percent_rank 가 아니라 cume_dist 를 쓰는 이유는 작은 집단에서 최저=0%·최고=100% 로 고정되는
     * 왜곡을 피하려는 것이다.
     *
     * <p>사업자번호 앞자리가 공란인 행은 백분위를 <b>저장</b>하지 않는다 — 원본 UNIQUE 제약이 NULL 을 서로
     * 다른 값으로 보기 때문에 같은 사업장명 + 공란 조합이 한 달에 여러 건 존재할 수 있고, 그러면 정규화된
     * 복합키가 충돌한다. 애초에 복합키 상세 조회로 지목할 수도 없다(조회는 숫자 6자리를 요구한다).
     *
     * <p>★ 다만 그 행들도 <b>순위 계산 모집단에는 포함</b>돼야 한다. SQL 은 WHERE 를 윈도우 함수보다 먼저
     * 적용하므로 필터를 안쪽에 두면 cume_dist 의 분모가 표본수보다 작아져 중앙값 모집단과 백분위 모집단이
     * 어긋난다(실 PostgreSQL 검증에서 표본 14 대 분모 12 로 재현). 그래서 창 함수를 먼저 계산하고
     * <b>바깥 질의에서</b> 걸러낸다.
     */
    private static final String INSERT_PERCENTILE = ELIGIBLE_GROUPS + """
            INSERT INTO workforce_percentile
                (snapshot_month, workplace_name, biz_reg_no_prefix, axis, level, metric, percentile)
            SELECT snapshot_month, workplace_name, biz_reg_no_prefix, axis, level, metric, percentile
            FROM (
                SELECT ? AS snapshot_month, workplace_name, biz_reg_no_prefix, axis, level,
                       'HEADCOUNT' AS metric,
                       ROUND((CUME_DIST() OVER (PARTITION BY axis, level, group_key
                                                ORDER BY headcount))::numeric * 100, 2) AS percentile
                FROM grouped
                UNION ALL
                SELECT ?, workplace_name, biz_reg_no_prefix, axis, level, 'ESTIMATED_ANNUAL_SALARY',
                       ROUND((CUME_DIST() OVER (PARTITION BY axis, level, group_key
                                                ORDER BY est_salary))::numeric * 100, 2)
                FROM grouped
            ) ranked
            WHERE biz_reg_no_prefix <> ''
            """;

    private static final String MARK_BUILDING = """
            INSERT INTO workforce_aggregate_build
                (snapshot_month, status, source_row_count, accepted_row_count, rejected_row_count, built_at,
                 source_release_date, source_sha256, raw_source_row_count, coverage_scope, region_scope, industry_scope)
            VALUES (?, 'BUILDING', ?, ?, ?, NOW(), NULL, NULL, NULL, NULL, NULL, NULL)
            ON CONFLICT (snapshot_month)
            DO UPDATE SET status = 'BUILDING',
                          source_row_count = EXCLUDED.source_row_count,
                          accepted_row_count = EXCLUDED.accepted_row_count,
                          rejected_row_count = EXCLUDED.rejected_row_count,
                          built_at = EXCLUDED.built_at,
                          source_release_date = NULL,
                          source_sha256 = NULL,
                          raw_source_row_count = NULL,
                          coverage_scope = NULL,
                          region_scope = NULL,
                          industry_scope = NULL
            """;

    private static final String MARK_COMPLETE =
            "UPDATE workforce_aggregate_build SET status = 'COMPLETE', built_at = NOW() WHERE snapshot_month = ?";

    private static final String DELETE_AGGREGATE = "DELETE FROM workforce_aggregate WHERE snapshot_month = ?";
    private static final String DELETE_PERCENTILE = "DELETE FROM workforce_percentile WHERE snapshot_month = ?";

    private final JdbcTemplate jdbcTemplate;

    public WorkforceAggregatePersistenceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void rebuild(YearMonth snapshotMonth, AggregateRowTally tally) {
        String month = snapshotMonth.toString();
        BigDecimal contributionRate = NpsContributionRate.rateOf(snapshotMonth)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported NPS contribution rate month: " + month));
        jdbcTemplate.update(MARK_BUILDING, month, tally.sourceRowCount(), tally.acceptedRowCount(),
                tally.rejectedRowCount());
        jdbcTemplate.update(DELETE_AGGREGATE, month);
        jdbcTemplate.update(DELETE_PERCENTILE, month);
        jdbcTemplate.update(INSERT_AGGREGATE, contributionRate, month, month);
        jdbcTemplate.update(INSERT_PERCENTILE, contributionRate, month, month, month);
        jdbcTemplate.update(MARK_COMPLETE, month);
    }
}
