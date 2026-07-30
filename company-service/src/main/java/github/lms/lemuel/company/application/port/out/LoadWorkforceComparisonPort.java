package github.lms.lemuel.company.application.port.out;

import github.lms.lemuel.company.domain.ComparisonAxis;
import github.lms.lemuel.company.domain.ComparisonLevel;
import github.lms.lemuel.company.domain.CompanyWorkforce;
import github.lms.lemuel.company.domain.WorkforceMetric;
import github.lms.lemuel.company.domain.WorkplaceKey;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * 비교 조회 전용 포트. 조회 경로는 <b>사전 집계를 읽기만</b> 한다 — 중앙값·순위·건수를 계산하지 않는다.
 */
public interface LoadWorkforceComparisonPort {

    /** 복합키로 대상 사업장 스냅샷 1건. */
    Optional<CompanyWorkforce> findByKey(WorkplaceKey key);

    /**
     * 한 집단(축 × 단계 × 집단키)의 사전 집계 통계 + 대상 사업장의 사전 계산 백분위.
     *
     * <p>해당 월의 집계 빌드가 COMPLETE 가 아니거나 집단 행이 없으면 빈 값이다(= 표본 0으로 취급).
     */
    Optional<GroupStatistics> findGroupStatistics(WorkplaceKey key, ComparisonAxis axis, ComparisonLevel level,
                                                  String groupKey);

    /**
     * @param sampleSize 집단의 적격 레코드 수 — 두 지표가 같은 집단을 공유하므로 하나뿐이다
     * @param byMetric   지표별 중앙값·백분위. 지표 행이 없으면 키가 없다
     */
    record GroupStatistics(int sampleSize, Map<WorkforceMetric, MetricStatistics> byMetric) {

        public GroupStatistics {
            byMetric = byMetric == null ? Map.of() : Map.copyOf(byMetric);
        }
    }

    /**
     * @param median     percentile_cont(0.5) 사전 집계값
     * @param percentile 대상 사업장의 cume_dist 사전 계산값. 대상이 적격 모집단에 없으면 null
     */
    record MetricStatistics(BigDecimal median, BigDecimal percentile) {
    }
}
