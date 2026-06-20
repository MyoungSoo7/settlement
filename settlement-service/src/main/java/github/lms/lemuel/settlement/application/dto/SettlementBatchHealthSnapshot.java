package github.lms.lemuel.settlement.application.dto;

import java.time.LocalDate;

/**
 * Settlement Batch Health Snapshot (순수 DTO, 스프링/JPA 의존성 없음)
 * 배치 헬스 체크를 위한 데이터 스냅샷
 */
public class SettlementBatchHealthSnapshot {

    private final LocalDate settlementDate;
    private final long settlementPendingCount;
    private final long settlementConfirmedCount;
    private final long adjustmentPendingCount;

    public SettlementBatchHealthSnapshot(LocalDate settlementDate,
                                         long settlementPendingCount,
                                         long settlementConfirmedCount,
                                         long adjustmentPendingCount) {
        this.settlementDate = settlementDate;
        this.settlementPendingCount = settlementPendingCount;
        this.settlementConfirmedCount = settlementConfirmedCount;
        this.adjustmentPendingCount = adjustmentPendingCount;
    }

    public LocalDate getSettlementDate() {
        return settlementDate;
    }

    public long getSettlementPendingCount() {
        return settlementPendingCount;
    }

    public long getSettlementConfirmedCount() {
        return settlementConfirmedCount;
    }

    public long getAdjustmentPendingCount() {
        return adjustmentPendingCount;
    }

    public boolean hasTooManyPendingSettlements() {
        return settlementPendingCount > 100;
    }

    public boolean hasTooManyPendingAdjustments() {
        return adjustmentPendingCount > 50;
    }
}
