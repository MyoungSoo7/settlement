package github.lms.lemuel.ledger.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 원장 역분개 백필 실행 결과 리포트.
 *
 * <p>INV-5 의 {@code missingReverseAdjustmentIds} 기준으로 차지백·PG 대사 조정 중
 * 역분개가 없는 건에 대해 {@code ledger_outbox} 작업을 적재한 결과를 기록한다.
 *
 * <p>{@code remainingMissing} 은 백필 직후 재조회한 "아직 역분개가 없는 건 수"이다.
 * 역분개 실제 생성은 {@code LedgerOutboxPoller} 가 비동기로 처리하므로, 백필 실행 직후에는
 * 잔여가 남을 수 있다. 폴러 처리 후 {@code GET /status} 로 재확인하면 0 이 되어야 한다.
 */
public record LedgerReverseBackfillReport(
        int pageSize,
        long enqueuedChargeback,      // 이번 실행에서 적재한 REVERSE_CHARGEBACK 건 수
        long enqueuedReconciliation,  // 이번 실행에서 적재한 REVERSE_RECONCILIATION 건 수
        long totalEnqueued,           // enqueuedChargeback + enqueuedReconciliation
        long remainingMissing,        // 실행 후 아직 역분개 없는 조정 건 수 (폴러 처리 전 잔여)
        int pagesCommitted,           // 커밋된 페이지 수
        boolean complete,             // remainingMissing == 0
        List<String> notes
) {

    /** 백필 실행 결과. */
    public static LedgerReverseBackfillReport of(int pageSize,
                                                  long enqueuedChargeback,
                                                  long enqueuedReconciliation,
                                                  long remainingMissing,
                                                  int pagesCommitted) {
        long totalEnqueued = enqueuedChargeback + enqueuedReconciliation;
        List<String> notes = new ArrayList<>();
        notes.add(String.format(
                "역분개 작업 적재 — 차지백 %d건 + PG대사 %d건 = 합계 %d건 / %d페이지 커밋 (페이지 크기 %d)",
                enqueuedChargeback, enqueuedReconciliation, totalEnqueued, pagesCommitted, pageSize));
        if (remainingMissing > 0) {
            notes.add("아직 역분개 없는 조정 " + remainingMissing
                    + "건 — ledger_outbox 폴러 처리 후 GET /status 재확인 권장 (비동기 처리 중)");
        } else {
            notes.add("역분개 누락 0 — 전체 보정 완료 (또는 폴러가 모두 처리함)");
        }
        return new LedgerReverseBackfillReport(
                pageSize, enqueuedChargeback, enqueuedReconciliation,
                totalEnqueued, remainingMissing, pagesCommitted,
                remainingMissing == 0, List.copyOf(notes));
    }

    /** 실행 없이 현황만 조회하는 응답. */
    public static LedgerReverseBackfillReport status(long remainingMissing) {
        List<String> notes = new ArrayList<>();
        notes.add(remainingMissing > 0
                ? "역분개 누락 " + remainingMissing + "건 — POST /run 으로 백필 실행 권장"
                : "역분개 누락 0 — 전체 정상");
        return new LedgerReverseBackfillReport(
                0, 0, 0, 0, remainingMissing, 0,
                remainingMissing == 0, List.copyOf(notes));
    }
}
