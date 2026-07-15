package github.lms.lemuel.report.domain;

import github.lms.lemuel.report.domain.exception.ReportInvariantViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CashflowBucket(
        LocalDate bucket,
        long transactionCount,
        BigDecimal gmv,
        BigDecimal refundedAmount,
        BigDecimal commissionAmount,
        BigDecimal netSettlement
) {
    public CashflowBucket {
        if (bucket == null) throw new ReportInvariantViolationException("bucket is required");
        gmv = nz(gmv);
        refundedAmount = nz(refundedAmount);
        commissionAmount = nz(commissionAmount);
        netSettlement = nz(netSettlement);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
