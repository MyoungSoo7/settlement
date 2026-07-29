package github.lms.lemuel.company.application.service;

import github.lms.lemuel.company.application.port.out.LoadWorkforceComparisonPort;
import github.lms.lemuel.company.application.port.out.LoadWorkforceComparisonPort.GroupStatistics;
import github.lms.lemuel.company.application.port.out.LoadWorkforceComparisonPort.MetricStatistics;
import github.lms.lemuel.company.domain.ComparisonAxis;
import github.lms.lemuel.company.domain.ComparisonLevel;
import github.lms.lemuel.company.domain.ComparisonUnavailableReason;
import github.lms.lemuel.company.domain.CompanyWorkforce;
import github.lms.lemuel.company.domain.GroupComparison;
import github.lms.lemuel.company.domain.WorkforceComparison;
import github.lms.lemuel.company.domain.WorkforceMetric;
import github.lms.lemuel.company.domain.WorkplaceKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkforceComparisonQueryServiceTest {

    private static final WorkplaceKey KEY = WorkplaceKey.of("주식회사에고이즘", "866759", "2026-06");

    private final LoadWorkforceComparisonPort port = mock(LoadWorkforceComparisonPort.class);
    private final WorkforceComparisonQueryService service = new WorkforceComparisonQueryService(port);

    /** 가입자 50명 · 고지 16,406,250 → 추정연봉 43,750,000. */
    private CompanyWorkforce workforce(String industryCode, String address) {
        return new CompanyWorkforce("주식회사에고이즘", "866759", industryCode, "전자상거래 소매업",
                address, YearMonth.of(2026, 6), 50, new BigDecimal("16406250"));
    }

    private GroupStatistics stats(int sampleSize) {
        return new GroupStatistics(sampleSize, Map.of(
                WorkforceMetric.HEADCOUNT, new MetricStatistics(new BigDecimal("12.5"), new BigDecimal("91.20")),
                WorkforceMetric.ESTIMATED_ANNUAL_SALARY,
                new MetricStatistics(new BigDecimal("35000000"), new BigDecimal("82.50"))));
    }

    private void givenWorkforce(String industryCode, String address) {
        when(port.findByKey(KEY)).thenReturn(Optional.of(workforce(industryCode, address)));
        when(port.findGroupStatistics(any(), any(), any(), any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("복합키가 어느 레코드와도 매칭되지 않으면 조회 실패로 알린다")
    void notFound() {
        when(port.findByKey(KEY)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.get(KEY));
    }

    @Test
    @DisplayName("세부 집단 표본이 충족되면 EXACT 단계로 두 지표를 모두 비교한다")
    void usesExactLevelWhenSampleIsEnough() {
        givenWorkforce("525101", "서울특별시 성동구 연무장19길");
        when(port.findGroupStatistics(KEY, ComparisonAxis.INDUSTRY, ComparisonLevel.EXACT, "525101"))
                .thenReturn(Optional.of(stats(12)));
        when(port.findGroupStatistics(KEY, ComparisonAxis.REGION, ComparisonLevel.EXACT, "서울특별시 성동구"))
                .thenReturn(Optional.of(stats(40)));

        WorkforceComparison result = service.get(KEY);

        GroupComparison industry = result.industryComparison();
        assertTrue(industry.isAvailable());
        assertEquals(ComparisonLevel.EXACT, industry.level());
        assertEquals("525101", industry.groupKey());
        assertEquals(12, industry.sampleSize());
        assertEquals("35000000", industry.estimatedAnnualSalary().median().toPlainString());
        assertEquals("8750000", industry.estimatedAnnualSalary().difference().toPlainString());
        assertEquals("25.00", industry.estimatedAnnualSalary().differenceRate().toPlainString());
        assertEquals("82.50", industry.estimatedAnnualSalary().percentile().toPlainString());
        assertEquals("12.5", industry.headcount().median().toPlainString());
        assertEquals("37.5", industry.headcount().difference().toPlainString());
        assertEquals("91.20", industry.headcount().percentile().toPlainString());

        assertEquals(ComparisonLevel.EXACT, result.regionComparison().level());
        assertEquals("서울특별시 성동구", result.regionComparison().groupKey());

        // 세부 단계가 충족되면 상위 단계는 조회하지 않는다.
        verify(port, never()).findGroupStatistics(KEY, ComparisonAxis.INDUSTRY, ComparisonLevel.BROADENED, "525");
    }

    @Test
    @DisplayName("세부 표본이 10건 미만이면 업종은 앞3자리, 지역은 시도 집단으로 한 단계만 넓힌다")
    void broadensOneStep() {
        givenWorkforce("525101", "서울특별시 성동구 연무장19길");
        when(port.findGroupStatistics(KEY, ComparisonAxis.INDUSTRY, ComparisonLevel.EXACT, "525101"))
                .thenReturn(Optional.of(stats(9)));
        when(port.findGroupStatistics(KEY, ComparisonAxis.INDUSTRY, ComparisonLevel.BROADENED, "525"))
                .thenReturn(Optional.of(stats(120)));
        when(port.findGroupStatistics(KEY, ComparisonAxis.REGION, ComparisonLevel.EXACT, "서울특별시 성동구"))
                .thenReturn(Optional.of(stats(3)));
        when(port.findGroupStatistics(KEY, ComparisonAxis.REGION, ComparisonLevel.BROADENED, "서울특별시"))
                .thenReturn(Optional.of(stats(5000)));

        WorkforceComparison result = service.get(KEY);

        assertEquals(ComparisonLevel.BROADENED, result.industryComparison().level());
        assertEquals("525", result.industryComparison().groupKey());
        assertEquals(120, result.industryComparison().sampleSize());
        assertEquals(ComparisonLevel.BROADENED, result.regionComparison().level());
        assertEquals("서울특별시", result.regionComparison().groupKey());
    }

    @Test
    @DisplayName("폴백 집단도 표본 미달이면 SAMPLE_TOO_SMALL + 마지막 시도 단계·집단키·표본수를 알린다")
    void sampleTooSmallAfterFallback() {
        givenWorkforce("525101", "서울특별시 성동구 연무장19길");
        when(port.findGroupStatistics(KEY, ComparisonAxis.INDUSTRY, ComparisonLevel.EXACT, "525101"))
                .thenReturn(Optional.of(stats(2)));
        when(port.findGroupStatistics(KEY, ComparisonAxis.INDUSTRY, ComparisonLevel.BROADENED, "525"))
                .thenReturn(Optional.of(stats(7)));

        GroupComparison industry = service.get(KEY).industryComparison();

        assertFalse(industry.isAvailable());
        assertEquals(ComparisonUnavailableReason.SAMPLE_TOO_SMALL, industry.unavailableReason());
        assertEquals(ComparisonLevel.BROADENED, industry.level());
        assertEquals("525", industry.groupKey());
        assertEquals(7, industry.sampleSize());
        assertNull(industry.headcount());
        assertNull(industry.estimatedAnnualSalary());
    }

    @Test
    @DisplayName("집계가 아직 만들어지지 않은 월(집단 행 없음)은 표본 0으로 보고 SAMPLE_TOO_SMALL")
    void missingAggregateIsTreatedAsZeroSample() {
        givenWorkforce("525101", "서울특별시 성동구 연무장19길");

        GroupComparison industry = service.get(KEY).industryComparison();

        assertEquals(ComparisonUnavailableReason.SAMPLE_TOO_SMALL, industry.unavailableReason());
        assertEquals(0, industry.sampleSize());
    }

    @Test
    @DisplayName("업종코드가 없으면 업종 비교만 INDUSTRY_CODE_MISSING — 지역 비교는 정상 제공된다")
    void industryCodeMissingDoesNotBlockRegion() {
        givenWorkforce(null, "서울특별시 성동구 연무장19길");
        when(port.findGroupStatistics(KEY, ComparisonAxis.REGION, ComparisonLevel.EXACT, "서울특별시 성동구"))
                .thenReturn(Optional.of(stats(40)));

        WorkforceComparison result = service.get(KEY);

        assertEquals(ComparisonUnavailableReason.INDUSTRY_CODE_MISSING,
                result.industryComparison().unavailableReason());
        assertNull(result.industryComparison().level());
        assertNull(result.industryComparison().groupKey());
        assertTrue(result.regionComparison().isAvailable());
        verify(port, never()).findGroupStatistics(any(), eq(ComparisonAxis.INDUSTRY), any(), any());
    }

    @Test
    @DisplayName("주소에서 시도를 못 뽑으면 지역 비교만 REGION_UNPARSEABLE — 업종 비교는 정상 제공된다")
    void regionUnparseableDoesNotBlockIndustry() {
        givenWorkforce("525101", "주소");
        when(port.findGroupStatistics(KEY, ComparisonAxis.INDUSTRY, ComparisonLevel.EXACT, "525101"))
                .thenReturn(Optional.of(stats(12)));

        WorkforceComparison result = service.get(KEY);

        assertEquals(ComparisonUnavailableReason.REGION_UNPARSEABLE,
                result.regionComparison().unavailableReason());
        assertTrue(result.industryComparison().isAvailable());
        verify(port, never()).findGroupStatistics(any(), eq(ComparisonAxis.REGION), any(), any());
    }

    @Test
    @DisplayName("세종특별자치시처럼 시군구가 없으면 시도 집단을 EXACT 조회 없이 바로 쓴다")
    void sejongGoesStraightToBroadenedRegion() {
        givenWorkforce("525101", "세종특별자치시 한누리대로 2130");
        when(port.findGroupStatistics(KEY, ComparisonAxis.REGION, ComparisonLevel.BROADENED, "세종특별자치시"))
                .thenReturn(Optional.of(stats(300)));

        GroupComparison region = service.get(KEY).regionComparison();

        assertTrue(region.isAvailable());
        assertEquals(ComparisonLevel.BROADENED, region.level());
        assertEquals("세종특별자치시", region.groupKey());
        verify(port, never()).findGroupStatistics(any(), eq(ComparisonAxis.REGION),
                eq(ComparisonLevel.EXACT), any());
    }

    @Test
    @DisplayName("업종코드가 3자리 이하면 상위 집단이 코드 자체라 같은 집단을 두 번 조회하지 않는다")
    void doesNotRetrySameGroupKey() {
        givenWorkforce("52", "서울특별시 성동구 연무장19길");
        when(port.findGroupStatistics(KEY, ComparisonAxis.INDUSTRY, ComparisonLevel.EXACT, "52"))
                .thenReturn(Optional.of(stats(4)));

        GroupComparison industry = service.get(KEY).industryComparison();

        assertEquals(ComparisonUnavailableReason.SAMPLE_TOO_SMALL, industry.unavailableReason());
        assertEquals(ComparisonLevel.EXACT, industry.level());
        verify(port, never()).findGroupStatistics(any(), eq(ComparisonAxis.INDUSTRY),
                eq(ComparisonLevel.BROADENED), any());
    }

    @Test
    @DisplayName("지표 통계가 일부만 있으면 그 지표만 null — 집단은 성립한 상태로 남는다")
    void partialMetricStatistics() {
        givenWorkforce("525101", "서울특별시 성동구 연무장19길");
        when(port.findGroupStatistics(KEY, ComparisonAxis.INDUSTRY, ComparisonLevel.EXACT, "525101"))
                .thenReturn(Optional.of(new GroupStatistics(30, Map.of(
                        WorkforceMetric.HEADCOUNT,
                        new MetricStatistics(new BigDecimal("12.5"), new BigDecimal("91.20"))))));

        GroupComparison industry = service.get(KEY).industryComparison();

        assertTrue(industry.isAvailable());
        assertEquals("12.5", industry.headcount().median().toPlainString());
        assertNull(industry.estimatedAnnualSalary());
    }

    @Test
    @DisplayName("백분위가 사전 계산되지 않은 지표는 비교 결과를 만들지 않는다 — 조회 시 순위를 세지 않는다")
    void missingPercentileYieldsNullMetric() {
        givenWorkforce("525101", "서울특별시 성동구 연무장19길");
        when(port.findGroupStatistics(KEY, ComparisonAxis.INDUSTRY, ComparisonLevel.EXACT, "525101"))
                .thenReturn(Optional.of(new GroupStatistics(30, Map.of(
                        WorkforceMetric.HEADCOUNT, new MetricStatistics(new BigDecimal("12.5"), null),
                        WorkforceMetric.ESTIMATED_ANNUAL_SALARY,
                        new MetricStatistics(new BigDecimal("35000000"), new BigDecimal("82.50"))))));

        GroupComparison industry = service.get(KEY).industryComparison();

        assertNull(industry.headcount());
        assertEquals("82.50", industry.estimatedAnnualSalary().percentile().toPlainString());
    }

    @Test
    @DisplayName("가입자수 0 사업장은 추정연봉이 없어 금액 지표 비교만 비어 있다")
    void zeroHeadcountHasNoSalaryMetric() {
        when(port.findByKey(KEY)).thenReturn(Optional.of(new CompanyWorkforce("주식회사에고이즘", "866759",
                "525101", "전자상거래 소매업", "서울특별시 성동구 연무장19길", YearMonth.of(2026, 6), 0,
                BigDecimal.ZERO)));
        when(port.findGroupStatistics(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(port.findGroupStatistics(KEY, ComparisonAxis.INDUSTRY, ComparisonLevel.EXACT, "525101"))
                .thenReturn(Optional.of(stats(30)));

        GroupComparison industry = service.get(KEY).industryComparison();

        assertEquals("-12.5", industry.headcount().difference().toPlainString());
        assertNull(industry.estimatedAnnualSalary());
    }
}
