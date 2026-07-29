package github.lms.lemuel.company.adapter.in.web.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import github.lms.lemuel.company.domain.ComparisonLevel;
import github.lms.lemuel.company.domain.ComparisonUnavailableReason;
import github.lms.lemuel.company.domain.CompanyWorkforce;
import github.lms.lemuel.company.domain.GroupComparison;
import github.lms.lemuel.company.domain.MetricComparison;
import github.lms.lemuel.company.domain.WorkforceComparison;
import github.lms.lemuel.company.domain.WorkplaceRegion;

import java.math.BigDecimal;

/**
 * 사업장 단건 상세 + 업종·지역 비교 응답.
 *
 * <p><b>금액 필드는 소수 문자열로 직렬화한다</b>({@code estimatedAnnualSalary}, {@code salaryCapMonthlyAmount},
 * 그리고 금액 지표의 {@code median}·{@code difference}). 부동소수 수치로 나가면 클라이언트 파싱 단계에서
 * 정밀도가 깨질 수 있어서다. 비율(증감률·백분위)과 건수(인원수·표본수)는 금액이 아니므로 수치로 나간다.
 *
 * <p>기존 목록 검색 응답({@link CompanyWorkforceResponse})은 손대지 않는다 — 별개 계약이다.
 *
 * <p>null 이 될 수 있는 필드: {@code industryCode}·{@code sido}·{@code sigungu}(원본 공란·주소 파싱 실패),
 * {@code estimatedAnnualSalary}(가입자수 0), {@code salaryCapMonthlyAmount}(고시표 범위 밖 기준월),
 * 비교 객체 안의 {@code level}·{@code groupKey}(집단 부재), {@code unavailableReason}(비교 성공),
 * 지표 하위 객체(비교 불가), {@code differenceRate}(중앙값 0). 비교 객체 자체는 사유 코드를 운반해야 하므로
 * 항상 존재한다.
 */
public record WorkforceComparisonResponse(String workplaceName, String bizRegNoPrefix, String snapshotMonth,
                                          String industryCode, String industryName, String address,
                                          String sido, String sigungu, int headcount,
                                          @JsonSerialize(using = ToStringSerializer.class)
                                          BigDecimal estimatedAnnualSalary,
                                          boolean salaryCapReached,
                                          @JsonSerialize(using = ToStringSerializer.class)
                                          BigDecimal salaryCapMonthlyAmount,
                                          GroupComparisonResponse industryComparison,
                                          GroupComparisonResponse regionComparison,
                                          String note) {

    private static final String CAP_DISCLAIMER =
            "국민연금 기준소득월액 상한 적용 추정치입니다 — 실제 급여와 다를 수 있습니다. "
                    + "수록 범위가 3인 이상 법인사업장(개인사업장은 10인 이상)이라 집단 중앙값은 절단 표본 기준입니다.";

    public static WorkforceComparisonResponse from(WorkforceComparison comparison) {
        CompanyWorkforce workforce = comparison.workforce();
        WorkplaceRegion region = workforce.region();
        return new WorkforceComparisonResponse(
                workforce.workplaceName(),
                workforce.bizRegNoPrefix(),
                workforce.snapshotMonth().toString(),
                workforce.industryGroupKey().orElse(null),
                workforce.industryName(),
                workforce.address(),
                region.sido(),
                region.sigungu(),
                workforce.headcount(),
                workforce.estimatedAnnualSalary().orElse(null),
                workforce.salaryCapReached(),
                workforce.salaryCapMonthlyAmount().orElse(null),
                GroupComparisonResponse.from(comparison.industryComparison()),
                GroupComparisonResponse.from(comparison.regionComparison()),
                CAP_DISCLAIMER);
    }

    /** 한 비교축의 판정 결과. 표본수·비교단계·사유는 축마다 하나뿐이다(두 지표가 같은 집단을 공유). */
    public record GroupComparisonResponse(ComparisonLevel comparisonLevel, String groupKey, int sampleSize,
                                          ComparisonUnavailableReason unavailableReason,
                                          HeadcountMetricResponse headcount,
                                          MoneyMetricResponse estimatedAnnualSalary) {

        static GroupComparisonResponse from(GroupComparison comparison) {
            return new GroupComparisonResponse(comparison.level(), comparison.groupKey(), comparison.sampleSize(),
                    comparison.unavailableReason(),
                    HeadcountMetricResponse.from(comparison.headcount()),
                    MoneyMetricResponse.from(comparison.estimatedAnnualSalary()));
        }
    }

    /** 인원수 지표 — 금액이 아니다. percentile_cont(0.5)가 짝수 표본에서 소수를 내므로 정수로 강제하지 않는다. */
    public record HeadcountMetricResponse(BigDecimal median, BigDecimal difference, BigDecimal differenceRate,
                                          BigDecimal percentile) {

        static HeadcountMetricResponse from(MetricComparison metric) {
            return metric == null ? null : new HeadcountMetricResponse(metric.median(), metric.difference(),
                    metric.differenceRate(), metric.percentile());
        }
    }

    /** 금액 지표 — median·difference 는 소수 문자열, 비율은 수치. */
    public record MoneyMetricResponse(@JsonSerialize(using = ToStringSerializer.class) BigDecimal median,
                                      @JsonSerialize(using = ToStringSerializer.class) BigDecimal difference,
                                      BigDecimal differenceRate, BigDecimal percentile) {

        static MoneyMetricResponse from(MetricComparison metric) {
            return metric == null ? null : new MoneyMetricResponse(metric.median(), metric.difference(),
                    metric.differenceRate(), metric.percentile());
        }
    }
}
