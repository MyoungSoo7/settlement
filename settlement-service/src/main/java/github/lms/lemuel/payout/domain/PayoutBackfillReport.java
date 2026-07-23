package github.lms.lemuel.payout.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 미생성 Payout 백필 실행 리포트.
 *
 * <p>INV-6 탐지 결과(확정됐지만 Payout 이 없는 정산)를 근거로, 지급유형별 Payout 을
 * 멱등·append-only 로 신규 생성한 결과를 담는다.
 *
 * <ul>
 *   <li>{@code created} — 이번 실행에서 신규 생성된 Payout 건수</li>
 *   <li>{@code skipped} — 이미 존재해 생략된 건수 (멱등 확인, UNIQUE 충돌 흡수)</li>
 *   <li>{@code failed} — 계좌 해석 실패 등 생성 불가 건수</li>
 *   <li>{@code remaining} — 실행 후 여전히 Payout 없는 DONE 정산 건수</li>
 *   <li>{@code pagesCommitted} — 커밋된 페이지 수</li>
 *   <li>{@code complete} — remaining == 0</li>
 * </ul>
 */
public record PayoutBackfillReport(
        LocalDate from,
        LocalDate to,
        int pageSize,
        long created,
        long skipped,
        long failed,
        long remaining,
        int pagesCommitted,
        boolean complete,
        List<String> notes
) {

    /** 백필 실행 결과 팩토리. */
    public static PayoutBackfillReport of(LocalDate from, LocalDate to,
                                          int pageSize, long created, long skipped,
                                          long failed, long remaining, int pagesCommitted) {
        List<String> notes = new ArrayList<>();
        notes.add(String.format("백필 실행: 생성 %d건 / 스킵(기존 존재) %d건 / 실패 %d건 / %d페이지 커밋",
                created, skipped, failed, pagesCommitted));
        if (remaining > 0) {
            notes.add("잔여 " + remaining + "건 미생성 — 계좌 미해석 또는 실행 중 신규 확정. 재실행 권장");
        } else {
            notes.add("잔여 0건 — 대상 기간 전량 Payout 생성 완료");
        }
        return new PayoutBackfillReport(from, to, pageSize,
                created, skipped, failed, remaining, pagesCommitted,
                remaining == 0, List.copyOf(notes));
    }

    /** 실행 없이 잔여 건수만 조회하는 상태 응답 팩토리. */
    public static PayoutBackfillReport status(LocalDate from, LocalDate to, long remaining) {
        List<String> notes = new ArrayList<>();
        notes.add(remaining > 0
                ? "미생성 Payout 대상 " + remaining + "건 (from=" + from + ", to=" + to + ")"
                : "미생성 Payout 없음 — 백필 불필요");
        return new PayoutBackfillReport(from, to, 0, 0, 0, 0, remaining,
                0, remaining == 0, List.copyOf(notes));
    }
}
