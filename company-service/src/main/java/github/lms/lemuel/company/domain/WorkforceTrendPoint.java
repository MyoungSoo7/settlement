package github.lms.lemuel.company.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;

/**
 * 시계열 1개월 지점 — 원시값(인원·추정연봉·상한 플래그) + 전월 대비 증감.
 *
 * <p>증감 4필드는 <b>연속된 인접 월(전월+1)일 때만</b> 계산되고, 첫 월·결측 갭 뒤 지점에서는
 * 전부 null 이다(보간 금지 — 시드 결정 2). 증감률은 전월 값이 0이거나 전월 추정연봉이 없으면
 * 정의되지 않아 null 이다({@link MetricComparison} 의 중앙값 0 규칙과 동형).
 *
 * <p>스케일: 인원 증감 정수(스케일 0)·추정연봉 증감 원 단위(스케일 0)·증감률 소수 2자리,
 * 전부 {@link RoundingMode#HALF_UP}.
 */
public record WorkforceTrendPoint(YearMonth month, int headcount, BigDecimal estimatedAnnualSalary,
                                  boolean salaryCapReached,
                                  BigDecimal headcountChange, BigDecimal headcountChangeRate,
                                  BigDecimal salaryChange, BigDecimal salaryChangeRate) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** 증감 없는 지점(첫 월·결측 갭 뒤). */
    static WorkforceTrendPoint withoutChange(CompanyWorkforce snapshot) {
        return new WorkforceTrendPoint(snapshot.snapshotMonth(), snapshot.headcount(),
                snapshot.estimatedAnnualSalary().orElse(null), snapshot.salaryCapReached(),
                null, null, null, null);
    }

    /** 연속 인접 월 지점 — 전월 스냅샷 대비 증감을 계산한다. */
    static WorkforceTrendPoint withChange(CompanyWorkforce snapshot, CompanyWorkforce previous) {
        BigDecimal headcountChange = BigDecimal.valueOf(snapshot.headcount() - previous.headcount());
        BigDecimal previousSalary = previous.estimatedAnnualSalary().orElse(null);
        BigDecimal currentSalary = snapshot.estimatedAnnualSalary().orElse(null);
        BigDecimal salaryChange = previousSalary == null || currentSalary == null
                ? null : currentSalary.subtract(previousSalary).setScale(0, RoundingMode.HALF_UP);
        return new WorkforceTrendPoint(snapshot.snapshotMonth(), snapshot.headcount(),
                currentSalary, snapshot.salaryCapReached(),
                headcountChange,
                rate(headcountChange, BigDecimal.valueOf(previous.headcount())),
                salaryChange,
                salaryChange == null ? null : rate(salaryChange, previousSalary));
    }

    /** 전월 값이 0이면 증감률은 정의되지 않는다(증감 자체는 제공한다). */
    private static BigDecimal rate(BigDecimal change, BigDecimal previousValue) {
        if (previousValue.signum() == 0) {
            return null;
        }
        return change.multiply(HUNDRED)
                .divide(previousValue, ComparisonPolicy.RATE_SCALE, RoundingMode.HALF_UP);
    }
}
