package github.lms.lemuel.company.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 한 지표의 집단 대비 비교 결과 — 중앙값, 원값 차이, 증감률, 백분위.
 *
 * <p>중앙값·백분위는 <b>계산하지 않고 받는다</b>. 조회 경로가 순위·건수를 세면 상세 조회 한 번이
 * 전국 집계로 번지므로, 두 값은 적재 시점 사전 집계(percentile_cont / cume_dist)의 산출물이다.
 * 이 타입이 계산하는 것은 대상 사업장 값에 대한 차이·증감률뿐이다.
 *
 * <p>모든 나눗셈은 스케일과 {@link RoundingMode#HALF_UP} 을 명시한다 — 금액 지표는 원 단위(스케일 0),
 * 인원수 지표는 소수 한 자리, 증감률·백분위는 소수 두 자리.
 */
public record MetricComparison(WorkforceMetric metric, BigDecimal median, BigDecimal difference,
                               BigDecimal differenceRate, BigDecimal percentile) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * @param targetValue 대상 사업장의 지표 원값
     * @param median      사전 집계된 집단 중앙값
     * @param percentile  사전 계산된 집단 내 백분위(cume_dist 의미, 0~100)
     */
    public static MetricComparison of(WorkforceMetric metric, BigDecimal targetValue, BigDecimal median,
                                      BigDecimal percentile) {
        if (metric == null) {
            throw new IllegalArgumentException("비교 지표는 필수입니다");
        }
        if (targetValue == null) {
            throw new IllegalArgumentException("대상 사업장 값은 필수입니다: " + metric);
        }
        if (median == null) {
            throw new IllegalArgumentException("집단 중앙값은 필수입니다: " + metric);
        }
        if (percentile == null) {
            throw new IllegalArgumentException("집단 내 백분위는 필수입니다: " + metric);
        }
        BigDecimal rawDifference = targetValue.subtract(median);
        return new MetricComparison(metric,
                median.setScale(metric.scale(), RoundingMode.HALF_UP),
                rawDifference.setScale(metric.scale(), RoundingMode.HALF_UP),
                differenceRate(rawDifference, median),
                percentile.setScale(ComparisonPolicy.RATE_SCALE, RoundingMode.HALF_UP));
    }

    /** 중앙값이 0이면 증감률은 정의되지 않는다(차이는 그대로 제공한다). */
    private static BigDecimal differenceRate(BigDecimal rawDifference, BigDecimal median) {
        if (median.signum() == 0) {
            return null;
        }
        return rawDifference.multiply(HUNDRED)
                .divide(median, ComparisonPolicy.RATE_SCALE, RoundingMode.HALF_UP);
    }
}
