package github.lms.lemuel.closing.adapter.in.web.response;

import github.lms.lemuel.closing.domain.ClosingTotals;
import github.lms.lemuel.closing.domain.MonthlyClosingRun;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 월마감 run 응답 — FAILED run 은 합계가 null. */
public record MonthlyClosingRunResponse(String periodYm, String status, String triggeredBy,
                                        OffsetDateTime startedAt, OffsetDateTime finishedAt,
                                        int sellerCount, long settlementCount,
                                        long unmappedCount, long pendingCount,
                                        BigDecimal totalGross, BigDecimal totalRefunded,
                                        BigDecimal totalCommission, BigDecimal totalHoldback,
                                        BigDecimal totalNet, String failureReason) {

    public static MonthlyClosingRunResponse from(MonthlyClosingRun run) {
        ClosingTotals totals = run.getTotals();
        return new MonthlyClosingRunResponse(
                run.getPeriodYm(), run.getStatus().name(), run.getTriggeredBy(),
                run.getStartedAt(), run.getFinishedAt(),
                run.getSellerCount(), run.getSettlementCount(),
                run.getUnmappedCount(), run.getPendingCount(),
                totals != null ? totals.grossAmount() : null,
                totals != null ? totals.refundedAmount() : null,
                totals != null ? totals.commissionAmount() : null,
                totals != null ? totals.holdbackAmount() : null,
                totals != null ? totals.netAmount() : null,
                run.getFailureReason());
    }
}
