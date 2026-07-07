package github.lms.lemuel.integrity.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * INV-11 상태 체류 리포트 — "중간 상태에 오래 머문 건 = 0".
 *
 * <p>감시 대상:
 * <ul>
 *   <li>settlement {@code PROCESSING} 장기 체류 + {@code settlement_date} 가 지났는데
 *       아직 미확정(REQUESTED/PENDING/PROCESSING)인 정산 — 확정 배치 누락 감지.</li>
 *   <li>payout {@code SENDING} 장기 체류 — 펌뱅킹 타임아웃. <b>이중지급 위험 1순위</b>:
 *       송금 성공 여부 불명 상태이므로 재시도 전 반드시 펌뱅킹 거래 조회로 확인해야 한다.</li>
 *   <li>PG 대사 {@code RUNNING} 장기 체류 — 크래시로 종료 처리 안 된 run.</li>
 *   <li>ledger_outbox {@code PENDING} 장기 체류 / {@code FAILED} — 원장 기록 경로 정지.</li>
 * </ul>
 */
public record StuckStateReport(
        int thresholdMinutes,
        List<StuckItem> stuckSettlements,        // PROCESSING 체류 초과
        List<StuckItem> overdueConfirmations,    // settlement_date 경과 미확정
        List<StuckPayout> stuckSendingPayouts,   // SENDING 체류 초과
        List<StuckItem> stuckPgReconRuns,        // RUNNING 체류 초과
        long stuckLedgerOutboxPending,           // PENDING 체류 초과 건수
        long ledgerOutboxFailed,
        boolean ok,
        List<String> reasons
) {

    public record StuckItem(Long id, String status, LocalDateTime since) {
    }

    /** SENDING payout 은 금액을 병기 — 이중지급 위험 판단에 즉시 필요하다. */
    public record StuckPayout(Long payoutId, Long settlementId, BigDecimal amount, LocalDateTime sentAt) {
    }

    public static StuckStateReport of(int thresholdMinutes,
                                      LocalDate today,
                                      List<StuckItem> stuckSettlements,
                                      List<StuckItem> overdueConfirmations,
                                      List<StuckPayout> stuckSendingPayouts,
                                      List<StuckItem> stuckPgReconRuns,
                                      long stuckLedgerOutboxPending,
                                      long ledgerOutboxFailed) {
        List<String> reasons = new ArrayList<>();
        if (!stuckSendingPayouts.isEmpty()) {
            reasons.add("SENDING 에 " + thresholdMinutes + "분 이상 머문 payout "
                    + stuckSendingPayouts.size() + "건 — 송금 성공 여부 불명. 재시도 전 펌뱅킹 거래 조회 필수 (이중지급 위험)");
        }
        if (!stuckSettlements.isEmpty()) {
            reasons.add("PROCESSING 에 머문 정산 " + stuckSettlements.size() + "건");
        }
        if (!overdueConfirmations.isEmpty()) {
            reasons.add("settlement_date(" + today + " 이전)가 지났는데 미확정인 정산 "
                    + overdueConfirmations.size() + "건 — 확정 배치 누락 의심");
        }
        if (!stuckPgReconRuns.isEmpty()) {
            reasons.add("RUNNING 에 머문 PG 대사 실행 " + stuckPgReconRuns.size() + "건");
        }
        if (stuckLedgerOutboxPending > 0) {
            reasons.add("ledger_outbox PENDING " + thresholdMinutes + "분 이상 체류 "
                    + stuckLedgerOutboxPending + "건 — 폴러 정지 의심");
        }
        if (ledgerOutboxFailed > 0) {
            reasons.add("ledger_outbox FAILED " + ledgerOutboxFailed + "건");
        }
        return new StuckStateReport(thresholdMinutes,
                List.copyOf(stuckSettlements), List.copyOf(overdueConfirmations),
                List.copyOf(stuckSendingPayouts), List.copyOf(stuckPgReconRuns),
                stuckLedgerOutboxPending, ledgerOutboxFailed,
                reasons.isEmpty(), List.copyOf(reasons));
    }
}
