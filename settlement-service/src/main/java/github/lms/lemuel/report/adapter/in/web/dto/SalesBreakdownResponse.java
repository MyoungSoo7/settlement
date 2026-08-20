package github.lms.lemuel.report.adapter.in.web.dto;

import github.lms.lemuel.report.domain.SalesBreakdown;
import github.lms.lemuel.report.domain.SalesDimension;
import github.lms.lemuel.report.domain.SalesShare;

import java.math.BigDecimal;
import java.util.List;

/**
 * 축별 매출 구성 응답.
 *
 * <p>{@code sharePercent} 는 이미 0~100 스케일이다 — 화면이 다시 100 을 곱하지 않도록
 * 서버가 표시 단위로 확정해 보낸다. 반올림 때문에 합이 정확히 100.00 이 아닐 수 있다.
 */
public record SalesBreakdownResponse(String dimension,
                                     long totalTransactionCount,
                                     BigDecimal totalGmv,
                                     List<Row> rows) {

    public static SalesBreakdownResponse from(SalesDimension dimension, SalesBreakdown breakdown) {
        return new SalesBreakdownResponse(
                dimension.name(),
                breakdown.totalTransactionCount(),
                breakdown.totalGmv(),
                breakdown.shares().stream().map(Row::of).toList());
    }

    public record Row(String label,
                      long transactionCount,
                      BigDecimal gmv,
                      BigDecimal refundedAmount,
                      BigDecimal commissionAmount,
                      BigDecimal netSettlement,
                      BigDecimal sharePercent) {
        static Row of(SalesShare share) {
            return new Row(share.label(), share.transactionCount(), share.gmv(),
                    share.refundedAmount(), share.commissionAmount(), share.netSettlement(),
                    share.sharePercent());
        }
    }
}
