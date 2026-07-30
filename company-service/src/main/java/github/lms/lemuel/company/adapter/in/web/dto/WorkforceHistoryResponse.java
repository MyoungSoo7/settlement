package github.lms.lemuel.company.adapter.in.web.dto;

import github.lms.lemuel.company.domain.WorkforceHistory;
import github.lms.lemuel.company.domain.WorkforceTrendPoint;
import github.lms.lemuel.company.domain.WorkplaceSeriesKey;

import java.math.BigDecimal;
import java.util.List;

/**
 * 사업장 월별 시계열 응답 — 월 오름차순 시리즈 + 인접 월 증감.
 *
 * <p><b>금액 필드는 소수 문자열이다</b>({@code estimatedAnnualSalary}·{@code salaryChange}) —
 * {@link WorkforceComparisonResponse} 와 같은 이유(Boot 4 런타임의 Jackson 3 가 Jackson 2
 * 직렬화기 애너테이션을 무시하므로 필드 타입 자체를 String 으로 둔다). 인원·비율은 수치.
 *
 * <p>null 이 될 수 있는 필드: 증감 4종(첫 월·결측 갭 뒤·전월값 0/부재), 추정연봉(가입자수 0).
 */
public record WorkforceHistoryResponse(String workplaceName, String bizRegNoPrefix,
                                       List<TrendPointResponse> series, String note) {

    private static final String NOTE =
            "국민연금 기준소득월액 상한 적용 추정치입니다 — 실제 급여와 다를 수 있습니다. "
                    + "수집을 건너뛴 달은 보간 없이 빠진 채 노출되며, 증감은 연속된 인접 월 사이에서만 계산됩니다. "
                    + "사업장명이 바뀌면 별개 시리즈로 단절됩니다.";

    public static WorkforceHistoryResponse from(WorkplaceSeriesKey key, WorkforceHistory history) {
        return new WorkforceHistoryResponse(
                key.workplaceName(),
                key.bizRegNoPrefix(),
                history.points().stream().map(TrendPointResponse::from).toList(),
                NOTE);
    }

    /** 금액 → 소수 문자열. null 은 그대로 null(JSON null). */
    private static String money(BigDecimal amount) {
        return amount == null ? null : amount.toPlainString();
    }

    /** 시계열 1개월 지점 — 증감은 연속 인접 월에만 존재한다. */
    public record TrendPointResponse(String snapshotMonth, int headcount, String estimatedAnnualSalary,
                                     boolean salaryCapReached,
                                     BigDecimal headcountChange, BigDecimal headcountChangeRate,
                                     String salaryChange, BigDecimal salaryChangeRate) {

        static TrendPointResponse from(WorkforceTrendPoint point) {
            return new TrendPointResponse(point.month().toString(), point.headcount(),
                    money(point.estimatedAnnualSalary()), point.salaryCapReached(),
                    point.headcountChange(), point.headcountChangeRate(),
                    money(point.salaryChange()), point.salaryChangeRate());
        }
    }
}
