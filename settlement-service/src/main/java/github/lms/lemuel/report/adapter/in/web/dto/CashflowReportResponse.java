package github.lms.lemuel.report.adapter.in.web.dto;

import github.lms.lemuel.report.domain.BucketGranularity;
import github.lms.lemuel.report.domain.CashflowBucket;
import github.lms.lemuel.report.domain.CashflowReconciliation;
import github.lms.lemuel.report.domain.CashflowReport;
import github.lms.lemuel.report.domain.CashflowTotals;
import github.lms.lemuel.report.domain.ReconciliationCheck;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CashflowReportResponse(
        Period period,
        Totals totals,
        List<Bucket> buckets,
        Reconciliation reconciliation
) {
    public static CashflowReportResponse from(CashflowReport report) {
        return new CashflowReportResponse(
                Period.of(report.from(), report.to(), report.granularity()),
                Totals.of(report.totals()),
                report.buckets().stream().map(Bucket::of).toList(),
                Reconciliation.of(report.reconciliation())
        );
    }

    public record Period(LocalDate from, LocalDate to, String groupBy) {
        static Period of(LocalDate from, LocalDate to, BucketGranularity g) {
            return new Period(from, to, g.name().toLowerCase());
        }
    }

    public record Totals(
            long transactionCount,
            BigDecimal gmv,
            BigDecimal refundedAmount,
            BigDecimal commissionAmount,
            BigDecimal netSettlement,
            BigDecimal refundRate
    ) {
        static Totals of(CashflowTotals t) {
            return new Totals(
                    t.transactionCount(),
                    t.gmv(),
                    t.refundedAmount(),
                    t.commissionAmount(),
                    t.netSettlement(),
                    t.refundRate()
            );
        }
    }

    public record Bucket(
            LocalDate bucket,
            long transactionCount,
            BigDecimal gmv,
            BigDecimal refundedAmount,
            BigDecimal commissionAmount,
            BigDecimal netSettlement
    ) {
        static Bucket of(CashflowBucket b) {
            return new Bucket(
                    b.bucket(),
                    b.transactionCount(),
                    b.gmv(),
                    b.refundedAmount(),
                    b.commissionAmount(),
                    b.netSettlement()
            );
        }
    }

    public record Reconciliation(
            boolean matched,
            int checksRun,
            List<Check> checks,
            List<Check> mismatches
    ) {
        static Reconciliation of(CashflowReconciliation r) {
            return new Reconciliation(
                    r.matched(),
                    r.checksRun(),
                    r.checks().stream().map(Check::of).toList(),
                    r.mismatches().stream().map(Check::of).toList()
            );
        }
    }

    public record Check(
            String name,
            boolean passed,
            BigDecimal expected,
            BigDecimal actual,
            BigDecimal discrepancy,
            String detail
    ) {
        static Check of(ReconciliationCheck c) {
            return new Check(c.name(), c.passed(), c.expected(),
                    c.actual(), c.discrepancy(), c.detail());
        }
    }
}
