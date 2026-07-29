package github.lms.lemuel.company.adapter.out.persistence;

import github.lms.lemuel.company.application.port.out.LoadWorkforceComparisonPort;
import github.lms.lemuel.company.domain.ComparisonAxis;
import github.lms.lemuel.company.domain.ComparisonLevel;
import github.lms.lemuel.company.domain.CompanyWorkforce;
import github.lms.lemuel.company.domain.WorkforceMetric;
import github.lms.lemuel.company.domain.WorkplaceKey;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 비교 조회 어댑터. 집계·백분위·빌드상태를 한 번의 조회로 합쳐 온다 — 조회 시점에 중앙값·순위·건수를
 * 계산하지 않는다(집계는 적재 시점에 이미 끝나 있다).
 */
@Component
public class WorkforceComparisonPersistenceAdapter implements LoadWorkforceComparisonPort {

    /**
     * 한 집단의 지표별 (중앙값, 표본수) + 대상 사업장의 백분위.
     *
     * <p>빌드 테이블을 COMPLETE 조건으로 조인해 <b>완성된 집계만</b> 읽는다. 백분위는 LEFT JOIN 이다 —
     * 대상 사업장이 적격 모집단에 없으면(가입자수 0 등) 행이 없고, 그 지표는 비교하지 않는다.
     */
    private static final String SELECT_GROUP_STATISTICS = """
            SELECT a.metric, a.median, a.sample_size, p.percentile
            FROM workforce_aggregate a
            JOIN workforce_aggregate_build b
              ON b.snapshot_month = a.snapshot_month AND b.status = 'COMPLETE'
            LEFT JOIN workforce_percentile p
              ON p.snapshot_month = a.snapshot_month
             AND p.axis = a.axis AND p.level = a.level AND p.metric = a.metric
             AND p.workplace_name = ? AND p.biz_reg_no_prefix = ?
            WHERE a.snapshot_month = ? AND a.axis = ? AND a.level = ? AND a.group_key = ?
            """;

    private final CompanyWorkforceRepository repository;
    private final JdbcTemplate jdbcTemplate;

    public WorkforceComparisonPersistenceAdapter(CompanyWorkforceRepository repository, JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CompanyWorkforce> findByKey(WorkplaceKey key) {
        return repository.findByWorkplaceNameAndBizRegNoPrefixAndSnapshotMonth(
                        key.workplaceName(), key.bizRegNoPrefix(), key.snapshotMonth().toString())
                .map(CompanyWorkforceJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GroupStatistics> findGroupStatistics(WorkplaceKey key, ComparisonAxis axis,
                                                         ComparisonLevel level, String groupKey) {
        List<MetricRow> rows = jdbcTemplate.query(SELECT_GROUP_STATISTICS,
                (rs, rowNum) -> new MetricRow(
                        WorkforceMetric.valueOf(rs.getString("metric")),
                        rs.getBigDecimal("median"),
                        rs.getInt("sample_size"),
                        rs.getBigDecimal("percentile")),
                key.workplaceName(), key.bizRegNoPrefix(), key.snapshotMonth().toString(),
                axis.name(), level.name(), groupKey);

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<WorkforceMetric, MetricStatistics> byMetric = new EnumMap<>(WorkforceMetric.class);
        for (MetricRow row : rows) {
            byMetric.put(row.metric(), new MetricStatistics(row.median(), row.percentile()));
        }
        // 표본수는 집단 속성이라 지표 행마다 같은 값이다 — 첫 행에서 읽는다.
        return Optional.of(new GroupStatistics(rows.get(0).sampleSize(), byMetric));
    }

    private record MetricRow(WorkforceMetric metric, BigDecimal median, int sampleSize, BigDecimal percentile) {
    }
}
