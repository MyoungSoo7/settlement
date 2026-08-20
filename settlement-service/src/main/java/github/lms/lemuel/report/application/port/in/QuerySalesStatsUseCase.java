package github.lms.lemuel.report.application.port.in;

import github.lms.lemuel.report.domain.ReportPeriod;
import github.lms.lemuel.report.domain.SalesBreakdown;
import github.lms.lemuel.report.domain.SalesComparison;
import github.lms.lemuel.report.domain.SalesDimension;

/**
 * 매출 통계 조회 유스케이스 — 운영 대시보드가 묻는 두 가지에 답한다.
 *
 * <ol>
 *   <li><b>얼마나 팔렸고, 지난번보다 나은가</b> → {@link #summary(ReportPeriod)}
 *   <li><b>그 매출이 어디서 나왔나</b> → {@link #breakdown(ReportPeriod, SalesDimension, int)}
 * </ol>
 *
 * <p>기간별 추이는 이미 있는 {@code GenerateCashflowReportUseCase}(일·주·월 버킷)가 답한다 —
 * 같은 집계를 두 벌 만들지 않는다.
 */
public interface QuerySalesStatsUseCase {

    /** 기간 요약 + 직전 동일 길이 기간과의 비교. */
    SalesSummary summary(ReportPeriod period);

    /** 축별 구성비(상위 {@code limit} 개). */
    SalesBreakdown breakdown(ReportPeriod period, SalesDimension dimension, int limit);

    /**
     * @param period         조회 기간
     * @param previousPeriod 비교 분모가 된 직전 기간 — 화면이 "무엇과 비교했는지" 밝힐 수 있어야 한다
     * @param comparison     양 기간의 합계와 증감률
     */
    record SalesSummary(ReportPeriod period, ReportPeriod previousPeriod, SalesComparison comparison) {
    }
}
