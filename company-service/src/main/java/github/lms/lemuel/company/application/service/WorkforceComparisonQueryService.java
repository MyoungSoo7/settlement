package github.lms.lemuel.company.application.service;

import github.lms.lemuel.company.application.port.in.GetWorkforceComparisonUseCase;
import github.lms.lemuel.company.application.port.out.LoadWorkforceComparisonPort;
import github.lms.lemuel.company.application.port.out.LoadWorkforceComparisonPort.GroupStatistics;
import github.lms.lemuel.company.application.port.out.LoadWorkforceComparisonPort.MetricStatistics;
import github.lms.lemuel.company.domain.ComparisonAxis;
import github.lms.lemuel.company.domain.ComparisonLevel;
import github.lms.lemuel.company.domain.ComparisonPolicy;
import github.lms.lemuel.company.domain.ComparisonUnavailableReason;
import github.lms.lemuel.company.domain.CompanyWorkforce;
import github.lms.lemuel.company.domain.GroupComparison;
import github.lms.lemuel.company.domain.MetricComparison;
import github.lms.lemuel.company.domain.WorkforceComparison;
import github.lms.lemuel.company.domain.WorkforceMetric;
import github.lms.lemuel.company.domain.WorkplaceKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * 사업장 단건 상세 비교 조회. 중앙값·표본수·백분위는 <b>전부 사전 집계에서 읽는다</b> — 이 서비스가
 * 계산하는 것은 폴백 단계 선택과 대상 값 대비 차이·증감률뿐이다(상세 조회 한 번이 전국 집계로
 * 번지지 않게 하는 성능 안전 경계).
 */
@Service
@Transactional(readOnly = true)
public class WorkforceComparisonQueryService implements GetWorkforceComparisonUseCase {

    private final LoadWorkforceComparisonPort loadWorkforceComparisonPort;

    public WorkforceComparisonQueryService(LoadWorkforceComparisonPort loadWorkforceComparisonPort) {
        this.loadWorkforceComparisonPort = loadWorkforceComparisonPort;
    }

    @Override
    public WorkforceComparison get(WorkplaceKey key) {
        CompanyWorkforce workforce = loadWorkforceComparisonPort.findByKey(key)
                .orElseThrow(() -> new NoSuchElementException(
                        "해당 사업장 스냅샷을 찾을 수 없습니다: " + key.workplaceName()
                                + " / " + key.bizRegNoPrefix() + " / " + key.snapshotMonth()));
        return new WorkforceComparison(workforce,
                resolve(key, workforce, ComparisonAxis.INDUSTRY,
                        workforce.industryGroupKey(), workforce.industryRollupKey(),
                        ComparisonUnavailableReason.INDUSTRY_CODE_MISSING),
                resolve(key, workforce, ComparisonAxis.REGION,
                        workforce.region().exactGroupKey(), workforce.region().broadenedGroupKey(),
                        ComparisonUnavailableReason.REGION_UNPARSEABLE));
    }

    /**
     * 세부 → 상위 순으로 <b>최대 2단계</b>만 시도하고 처음으로 표본을 충족한 집단을 쓴다. 두 축은 서로의
     * 성패에 관여하지 않는다.
     *
     * <p>세부 단계가 충족되면 상위 단계는 조회하지 않는다. 세부 키가 아예 없으면(시군구 없는 주소) 상위
     * 단계부터 시작하고, 두 키가 같으면(3자리 이하 업종코드) 같은 집단을 두 번 조회하지 않는다.
     * 집단 키를 하나도 만들 수 없으면 집단 부재 사유({@code noGroupReason})로 끝난다.
     */
    private GroupComparison resolve(WorkplaceKey key, CompanyWorkforce workforce, ComparisonAxis axis,
                                    Optional<String> exactGroupKey, Optional<String> broadenedGroupKey,
                                    ComparisonUnavailableReason noGroupReason) {
        ComparisonLevel attemptedLevel = null;
        String attemptedGroupKey = null;
        int attemptedSampleSize = 0;

        for (Attempt attempt : attempts(exactGroupKey, broadenedGroupKey)) {
            Optional<GroupStatistics> statistics = loadWorkforceComparisonPort
                    .findGroupStatistics(key, axis, attempt.level(), attempt.groupKey());
            int sampleSize = statistics.map(GroupStatistics::sampleSize).orElse(0);
            if (ComparisonPolicy.hasEnoughSample(sampleSize)) {
                return GroupComparison.available(axis, attempt.level(), attempt.groupKey(), sampleSize,
                        metricComparison(WorkforceMetric.HEADCOUNT, workforce, statistics.orElseThrow()),
                        metricComparison(WorkforceMetric.ESTIMATED_ANNUAL_SALARY, workforce,
                                statistics.orElseThrow()));
            }
            attemptedLevel = attempt.level();
            attemptedGroupKey = attempt.groupKey();
            attemptedSampleSize = sampleSize;
        }
        if (attemptedLevel == null) {
            return GroupComparison.noGroup(axis, noGroupReason);
        }
        return GroupComparison.sampleTooSmall(axis, attemptedLevel, attemptedGroupKey, attemptedSampleSize);
    }

    private static List<Attempt> attempts(Optional<String> exactGroupKey, Optional<String> broadenedGroupKey) {
        List<Attempt> attempts = new ArrayList<>(2);
        exactGroupKey.ifPresent(groupKey -> attempts.add(new Attempt(ComparisonLevel.EXACT, groupKey)));
        broadenedGroupKey
                .filter(groupKey -> exactGroupKey.filter(groupKey::equals).isEmpty())
                .ifPresent(groupKey -> attempts.add(new Attempt(ComparisonLevel.BROADENED, groupKey)));
        return attempts;
    }

    /**
     * 지표 하나의 비교 결과. 대상 값이 없거나(가입자수 0 → 추정연봉 없음) 사전 계산된 중앙값·백분위가
     * 없으면 그 지표만 비운다 — 집단은 이미 성립했으므로 사유 코드를 붙이지 않고, 조회 시점에 순위를
     * 다시 세지도 않는다.
     */
    private static MetricComparison metricComparison(WorkforceMetric metric, CompanyWorkforce workforce,
                                                     GroupStatistics statistics) {
        Optional<BigDecimal> targetValue = workforce.valueOf(metric);
        MetricStatistics metricStatistics = statistics.byMetric().get(metric);
        if (targetValue.isEmpty() || metricStatistics == null
                || metricStatistics.median() == null || metricStatistics.percentile() == null) {
            return null;
        }
        return MetricComparison.of(metric, targetValue.get(), metricStatistics.median(),
                metricStatistics.percentile());
    }

    private record Attempt(ComparisonLevel level, String groupKey) {
    }
}
