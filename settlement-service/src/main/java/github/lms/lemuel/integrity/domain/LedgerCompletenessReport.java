package github.lms.lemuel.integrity.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * INV-5 원장 완전성 리포트 — "확정된 정산에는 분개가 반드시 존재한다".
 *
 * <p>시산표(차/대 균형)는 분개가 <b>통짜로 누락</b>된 정산을 잡지 못한다(양변이 같이 없으면
 * 균형은 유지). 이 리포트는 확정(DONE) 정산 ↔ {@code reference_type=SETTLEMENT} 분개,
 * 그리고 3개 출처의 조정(환불·차지백·PG 대사) ↔ 각 {@code reference_type}(REFUND·CHARGEBACK·
 * PG_RECONCILIATION) 역분개의 존재·금액 일치를 정면으로 대조한다.
 *
 * <p>원장 기록은 {@code ledger_outbox} 를 경유하는 비동기 경로이므로, grace window 안의
 * 미처리분은 불일치가 아니라 {@code pendingWithinGrace} 로 구분한다 (오탐 방지).
 */
public record LedgerCompletenessReport(
        LocalDate targetDate,
        int graceMinutes,
        long confirmedSettlements,          // 그날 확정(DONE) 정산 건수
        BigDecimal confirmedPaymentTotal,   // 그날 확정 정산 payment_amount 합 (분개 기대 총액)
        long ledgerEntryRows,               // 그 정산들의 SETTLEMENT 분개 row 수
        BigDecimal ledgerPostedTotal,       // 그 정산들의 SETTLEMENT 분개 금액 합
        List<Long> missingSettlementIds,    // 분개가 아예 없는 확정 정산 (grace 경과분만, 상한 절단)
        long pendingWithinGrace,            // 분개 없음 but grace 이내 — 정상 대기
        List<Long> amountMismatchedSettlementIds, // 분개는 있는데 합계 ≠ payment_amount (반쪽 분개)
        List<Long> missingReverseAdjustmentIds, // 조정(환불·차지백·PG대사)은 있는데 역분개 없는 adjustment id (grace 경과분)
        long ledgerOutboxPending,
        long ledgerOutboxFailed,
        long ledgerOutboxOldestPendingAgeSec,
        boolean ok,
        List<String> reasons
) {

    public static LedgerCompletenessReport of(LocalDate targetDate,
                                              int graceMinutes,
                                              long confirmedSettlements,
                                              BigDecimal confirmedPaymentTotal,
                                              long ledgerEntryRows,
                                              BigDecimal ledgerPostedTotal,
                                              List<Long> missingSettlementIds,
                                              long pendingWithinGrace,
                                              List<Long> amountMismatchedSettlementIds,
                                              List<Long> missingReverseAdjustmentIds,
                                              long ledgerOutboxPending,
                                              long ledgerOutboxFailed,
                                              long ledgerOutboxOldestPendingAgeSec) {
        List<String> reasons = new ArrayList<>();
        if (!missingSettlementIds.isEmpty()) {
            reasons.add("확정 정산 " + missingSettlementIds.size()
                    + "건에 SETTLEMENT 분개가 없습니다 (grace " + graceMinutes + "분 경과) — INV-5 위반");
        }
        if (!amountMismatchedSettlementIds.isEmpty()) {
            reasons.add("분개 합계가 payment_amount 와 다른 정산 "
                    + amountMismatchedSettlementIds.size() + "건 — 반쪽 분개 의심 (INV-5)");
        }
        if (!missingReverseAdjustmentIds.isEmpty()) {
            reasons.add("조정(환불·차지백·PG대사) " + missingReverseAdjustmentIds.size()
                    + "건에 대응 역분개가 없습니다 (grace 경과) — INV-5/INV-8 위반");
        }
        if (ledgerOutboxFailed > 0) {
            reasons.add("ledger_outbox FAILED " + ledgerOutboxFailed
                    + "건 — 원장 작업이 죽어 있습니다. last_error 확인 후 재처리 필요");
        }
        return new LedgerCompletenessReport(targetDate, graceMinutes,
                confirmedSettlements, confirmedPaymentTotal,
                ledgerEntryRows, ledgerPostedTotal,
                List.copyOf(missingSettlementIds), pendingWithinGrace,
                List.copyOf(amountMismatchedSettlementIds), List.copyOf(missingReverseAdjustmentIds),
                ledgerOutboxPending, ledgerOutboxFailed, ledgerOutboxOldestPendingAgeSec,
                reasons.isEmpty(), List.copyOf(reasons));
    }
}
