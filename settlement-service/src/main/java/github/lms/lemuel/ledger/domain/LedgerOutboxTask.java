package github.lms.lemuel.ledger.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 원장 아웃박스 작업 1건 (도메인 표현).
 *
 * <p>{@code CREATE_ENTRY} 는 {@code settlementId} 만, 역분개 작업({@code REVERSE_ENTRY}·
 * {@code REVERSE_CHARGEBACK}·{@code REVERSE_RECONCILIATION})은 {@code refundId}/{@code refundAmount}
 * 필드를 <b>출처별 참조(referenceId, amount)</b> 로 재사용한다 — 환불이면 refundId, 차지백이면
 * chargebackId, 대사면 discrepancyId. 신규 생성 시 {@code id} 는 null.
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

    /** 환불 역분개 작업. reference = (refundId, refundAmount). */
    public static LedgerOutboxTask reverse(Long settlementId, Long refundId,
                                           BigDecimal refundAmount, LocalDate adjustmentDate) {
        return new LedgerOutboxTask(null, LedgerTaskType.REVERSE_ENTRY, settlementId,
                refundId, refundAmount, adjustmentDate, 0);
    }

    /** 차지백 ACCEPTED 역분개 작업. reference = (chargebackId, amount). */
    public static LedgerOutboxTask reverseChargeback(Long settlementId, Long chargebackId,
                                                     BigDecimal amount, LocalDate adjustmentDate) {
        return new LedgerOutboxTask(null, LedgerTaskType.REVERSE_CHARGEBACK, settlementId,
                chargebackId, amount, adjustmentDate, 0);
    }

    /** PG 대사 clawback 역분개 작업. reference = (discrepancyId, amount). */
    public static LedgerOutboxTask reverseReconciliation(Long settlementId, Long discrepancyId,
                                                         BigDecimal amount, LocalDate adjustmentDate) {
        return new LedgerOutboxTask(null, LedgerTaskType.REVERSE_RECONCILIATION, settlementId,
                discrepancyId, amount, adjustmentDate, 0);
    }
}
