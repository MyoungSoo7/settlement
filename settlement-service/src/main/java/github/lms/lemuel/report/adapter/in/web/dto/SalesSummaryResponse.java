package github.lms.lemuel.report.adapter.in.web.dto;

import github.lms.lemuel.report.application.port.in.QuerySalesStatsUseCase.SalesSummary;
import github.lms.lemuel.report.domain.CashflowTotals;
import github.lms.lemuel.report.domain.ReportPeriod;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 매출 요약 응답 — 이번 기간 · 직전 기간 · 증감률.
 *
 * <p>비교 대상 기간을 응답에 실어 보내는 이유: 화면이 "무엇과 비교한 수치인지" 밝히지 못하면
 * 운영자는 증감률을 신뢰할 근거가 없다.
 *
 * <p>증감률은 <b>null 일 수 있다</b> — 직전 기간에 거래가 없었다는 뜻이며, 화면은 이를
 * 0% 가 아니라 "—"로 그려야 한다.
 */
public record SalesSummaryResponse(Period period,
                                   Period previousPeriod,
                                   Totals current,
                                   Totals previous,
                                   Growth growth) {

    public static SalesSummaryResponse from(SalesSummary summary) {
        return new SalesSummaryResponse(
                Period.of(summary.period()),
                Period.of(summary.previousPeriod()),
                Totals.of(summary.comparison().current()),
                Totals.of(summary.comparison().previous()),
                new Growth(summary.comparison().gmvGrowthRate(),
                        summary.comparison().netGrowthRate(),
                        summary.comparison().countGrowthRate()));
    }

    public record Period(LocalDate from, LocalDate to, long days) {
        static Period of(ReportPeriod period) {
            return new Period(period.from(), period.to(), period.days());
        }
    }

    public record Totals(long transactionCount,
                         BigDecimal gmv,
                         BigDecimal refundedAmount,
                         BigDecimal commissionAmount,
                         BigDecimal netSettlement,
                         BigDecimal refundRate) {
        static Totals of(CashflowTotals totals) {
            return new Totals(totals.transactionCount(), totals.gmv(), totals.refundedAmount(),
                    totals.commissionAmount(), totals.netSettlement(), totals.refundRate());
        }
    }

    /** 비율 스케일은 소수 4자리(1.0000 = +100%). 직전 기간이 0 이면 null. */
    public record Growth(BigDecimal gmv, BigDecimal netSettlement, BigDecimal transactionCount) {
    }
}
