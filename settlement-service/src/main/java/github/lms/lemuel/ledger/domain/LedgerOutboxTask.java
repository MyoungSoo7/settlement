package github.lms.lemuel.ledger.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 원장 아웃박스 작업 1건 (도메인 표현).
 *
 * <p>{@code CREATE_ENTRY} 는 {@code settlementId} 만, {@code REVERSE_ENTRY} 는 refund
 * 필드까지 채워진다. 신규 생성 시 {@code id} 는 null.
 */
public record LedgerOutboxTask(
        Long id,
        LedgerTaskType type,
        Long settlementId,
        Long refundId,
        BigDecimal refundAmount,
        LocalDate adjustmentDate,
        int retryCount) {

    /** 정산 확정 분개 작업. */
    public static LedgerOutboxTask create(Long settlementId) {
        return new LedgerOutboxTask(null, LedgerTaskType.CREATE_ENTRY, settlementId,
                null, null, null, 0);
    }

    /** 환불 역분개 작업. */
    public static LedgerOutboxTask reverse(Long settlementId, Long refundId,
                                           BigDecimal refundAmount, LocalDate adjustmentDate) {
        return new LedgerOutboxTask(null, LedgerTaskType.REVERSE_ENTRY, settlementId,
                refundId, refundAmount, adjustmentDate, 0);
    }
}
