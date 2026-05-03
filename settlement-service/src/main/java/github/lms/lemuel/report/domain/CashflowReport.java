package github.lms.lemuel.report.domain;

import java.time.LocalDate;
import java.util.List;

public record CashflowReport(
        LocalDate from,
        LocalDate to,
        BucketGranularity granularity,
        CashflowTotals totals,
        List<CashflowBucket> buckets,
        CashflowReconciliation reconciliation
) {
    public CashflowReport {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from/to are required");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must be <= to");
        }
        if (granularity == null) {
            throw new IllegalArgumentException("granularity is required");
        }
        if (buckets == null) {
            buckets = List.of();
        }
        if (totals == null) {
            totals = CashflowTotals.from(buckets);
        }
        if (reconciliation == null) {
            reconciliation = CashflowReconciliation.empty();
        }
    }

    public static CashflowReport of(LocalDate from, LocalDate to, BucketGranularity granularity,
                                    List<CashflowBucket> buckets,
                                    CashflowReconciliation reconciliation) {
        return new CashflowReport(from, to, granularity,
                CashflowTotals.from(buckets), buckets, reconciliation);
    }

    /** 호환용 — reconciliation 없이 생성. 기존 테스트가 사용. */
    public static CashflowReport of(LocalDate from, LocalDate to, BucketGranularity granularity,
                                    List<CashflowBucket> buckets) {
        return of(from, to, granularity, buckets, CashflowReconciliation.empty());
    }
}
