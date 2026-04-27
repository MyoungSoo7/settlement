package github.lms.lemuel.report.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public record CashflowTotals(
        long transactionCount,
        BigDecimal gmv,
        BigDecimal refundedAmount,
        BigDecimal commissionAmount,
        BigDecimal netSettlement,
        BigDecimal refundRate
) {
    /**
     * 버킷 합으로부터 기간 전체 합계를 계산.
     * refundRate = refundedAmount / gmv  (소수점 4자리 반올림, gmv=0 이면 0)
     */
    public static CashflowTotals from(List<CashflowBucket> buckets) {
        long txCount = 0L;
        BigDecimal gmv = BigDecimal.ZERO;
        BigDecimal refunded = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;

        for (CashflowBucket b : buckets) {
            txCount += b.transactionCount();
            gmv = gmv.add(b.gmv());
            refunded = refunded.add(b.refundedAmount());
            commission = commission.add(b.commissionAmount());
            net = net.add(b.netSettlement());
        }

        BigDecimal refundRate = gmv.compareTo(BigDecimal.ZERO) > 0
                ? refunded.divide(gmv, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return new CashflowTotals(txCount, gmv, refunded, commission, net, refundRate);
    }
}
