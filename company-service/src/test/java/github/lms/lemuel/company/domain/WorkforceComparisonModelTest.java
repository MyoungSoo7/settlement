package github.lms.lemuel.company.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkforceComparisonModelTest {

    @Nested
    class Metric {

        @Test
        @DisplayName("금액 지표는 원 단위(스케일 0), 증감률·백분위는 소수 2자리로 고정한다")
        void moneyMetricScales() {
            MetricComparison comparison = MetricComparison.of(WorkforceMetric.ESTIMATED_ANNUAL_SALARY,
                    new BigDecimal("43750000"), new BigDecimal("35000000"), new BigDecimal("82.5"));

            assertEquals("35000000", comparison.median().toPlainString());
            assertEquals("8750000", comparison.difference().toPlainString());
            assertEquals("25.00", comparison.differenceRate().toPlainString());
            assertEquals("82.50", comparison.percentile().toPlainString());
        }

        @Test
        @DisplayName("인원수 지표의 중앙값·차이는 소수 1자리 — percentile_cont(0.5)가 짝수 표본에서 소수를 낸다")
        void headcountMetricKeepsOneDecimal() {
            MetricComparison comparison = MetricComparison.of(WorkforceMetric.HEADCOUNT,
                    new BigDecimal("50"), new BigDecimal("12.5"), new BigDecimal("91"));

            assertEquals("12.5", comparison.median().toPlainString());
            assertEquals("37.5", comparison.difference().toPlainString());
            assertEquals("300.00", comparison.differenceRate().toPlainString());
        }

        @Test
        @DisplayName("중앙값이 0이면 증감률만 null — 차이는 그대로 제공한다")
        void nullRateOnZeroMedian() {
            MetricComparison comparison = MetricComparison.of(WorkforceMetric.HEADCOUNT,
                    new BigDecimal("4"), BigDecimal.ZERO, new BigDecimal("100"));

            assertEquals("4.0", comparison.difference().toPlainString());
            assertNull(comparison.differenceRate());
        }

        @Test
        @DisplayName("중앙값보다 작으면 차이·증감률이 음수다")
        void negativeDifference() {
            MetricComparison comparison = MetricComparison.of(WorkforceMetric.ESTIMATED_ANNUAL_SALARY,
                    new BigDecimal("30000000"), new BigDecimal("40000000"), new BigDecimal("12.34"));

            assertEquals("-10000000", comparison.difference().toPlainString());
            assertEquals("-25.00", comparison.differenceRate().toPlainString());
        }

        @Test
        @DisplayName("증감률 라운딩은 HALF_UP — 정확히 0.005% 는 0.01 로 올린다")
        void roundsRateHalfUp() {
            MetricComparison comparison = MetricComparison.of(WorkforceMetric.HEADCOUNT,
                    new BigDecimal("200010"), new BigDecimal("200000"), BigDecimal.ZERO);

            assertEquals("10.0", comparison.difference().toPlainString());
            assertEquals("0.01", comparison.differenceRate().toPlainString());
        }

        @Test
        @DisplayName("지표·값·중앙값·백분위 누락은 거부한다")
        void rejectsNulls() {
            assertThrows(IllegalArgumentException.class, () -> MetricComparison.of(null,
                    BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
            assertThrows(IllegalArgumentException.class, () -> MetricComparison.of(WorkforceMetric.HEADCOUNT,
                    null, BigDecimal.ONE, BigDecimal.ONE));
            assertThrows(IllegalArgumentException.class, () -> MetricComparison.of(WorkforceMetric.HEADCOUNT,
                    BigDecimal.ONE, null, BigDecimal.ONE));
            assertThrows(IllegalArgumentException.class, () -> MetricComparison.of(WorkforceMetric.HEADCOUNT,
                    BigDecimal.ONE, BigDecimal.ONE, null));
        }
    }

    @Nested
    class Group {

        private MetricComparison headcount() {
            return MetricComparison.of(WorkforceMetric.HEADCOUNT,
                    new BigDecimal("50"), new BigDecimal("10"), new BigDecimal("90"));
        }

        @Test
        @DisplayName("표본이 최소 기준 이상이면 비교 성립 — 사유 코드는 없다")
        void availableGroup() {
            GroupComparison group = GroupComparison.available(ComparisonAxis.INDUSTRY, ComparisonLevel.EXACT,
                    "525101", 12, headcount(), null);

            assertTrue(group.isAvailable());
            assertNull(group.unavailableReason());
            assertEquals(12, group.sampleSize());
            assertEquals(ComparisonLevel.EXACT, group.level());
        }

        @Test
        @DisplayName("표본 미달 집단으로 비교 성립을 주장할 수 없다")
        void rejectsAvailableBelowMinimumSample() {
            assertThrows(IllegalArgumentException.class, () -> GroupComparison.available(
                    ComparisonAxis.INDUSTRY, ComparisonLevel.EXACT, "525101", 9, headcount(), null));
        }

        @Test
        @DisplayName("표본 미달은 마지막으로 시도한 단계·집단키·표본수를 함께 알려준다 (설명가능성)")
        void sampleTooSmallKeepsAttemptContext() {
            GroupComparison group = GroupComparison.sampleTooSmall(ComparisonAxis.INDUSTRY,
                    ComparisonLevel.BROADENED, "525", 7);

            assertFalse(group.isAvailable());
            assertEquals(ComparisonUnavailableReason.SAMPLE_TOO_SMALL, group.unavailableReason());
            assertEquals(ComparisonLevel.BROADENED, group.level());
            assertEquals("525", group.groupKey());
            assertEquals(7, group.sampleSize());
            assertNull(group.headcount());
            assertNull(group.estimatedAnnualSalary());
        }

        @Test
        @DisplayName("업종코드 없음·지역 파싱 실패는 집단 자체가 없어 단계·집단키가 null 이다")
        void noGroupAtAll() {
            GroupComparison industry = GroupComparison.noGroup(ComparisonAxis.INDUSTRY,
                    ComparisonUnavailableReason.INDUSTRY_CODE_MISSING);
            GroupComparison region = GroupComparison.noGroup(ComparisonAxis.REGION,
                    ComparisonUnavailableReason.REGION_UNPARSEABLE);

            assertNull(industry.level());
            assertNull(industry.groupKey());
            assertEquals(0, industry.sampleSize());
            assertEquals(ComparisonUnavailableReason.INDUSTRY_CODE_MISSING, industry.unavailableReason());
            assertEquals(ComparisonUnavailableReason.REGION_UNPARSEABLE, region.unavailableReason());
        }

        @Test
        @DisplayName("사유 코드는 축에 맞는 것만 붙일 수 있다 — 지역 사유를 업종축에 달 수 없다")
        void reasonMustMatchAxis() {
            assertThrows(IllegalArgumentException.class, () -> GroupComparison.noGroup(
                    ComparisonAxis.INDUSTRY, ComparisonUnavailableReason.REGION_UNPARSEABLE));
            assertThrows(IllegalArgumentException.class, () -> GroupComparison.noGroup(
                    ComparisonAxis.REGION, ComparisonUnavailableReason.INDUSTRY_CODE_MISSING));
        }
    }

    @Nested
    class Policy {

        @Test
        @DisplayName("비교 집단 최소 표본 수는 10건이다")
        void minimumSampleSizeIsTen() {
            assertEquals(10, ComparisonPolicy.MIN_SAMPLE_SIZE);
            assertFalse(ComparisonPolicy.hasEnoughSample(9));
            assertTrue(ComparisonPolicy.hasEnoughSample(10));
            assertTrue(ComparisonPolicy.hasEnoughSample(11));
        }
    }
}
