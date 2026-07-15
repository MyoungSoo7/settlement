package github.lms.lemuel.payout.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 지급계좌 PII 재암호화 백필 리포트 — "레거시 평문(enc:v1 접두 없음) 행을 몇 건 재암호화했고 몇 건이 남았는가".
 *
 * <p>배경: 지급계좌 PII 는 앱단 AES-GCM lazy migration(enc:v1 스킴, {@code PayoutFieldEncryptionConverter})으로
 * 저장 시 암호화되지만, 암호화 도입 이전 적재된 평문 행은 재저장 전까지 무기한 잔존한다. 이 백필은 그 평문 행을
 * 페이지 단위로 재저장해 암호문으로 강제 전환하고, 실행 후 평문 잔존 건수를 함께 노출해 완료 여부를 기계 판정한다.
 *
 * <p>{@code complete} = 실행 후 평문 잔존 0. 운영자/에이전트는 이 플래그로 재실행 필요 여부를 판단한다.
 */
public record PayoutPiiBackfillReport(
        int pageSize,             // 페이지(=트랜잭션) 단위 처리 건수
        long backfilled,          // 이번 실행에서 재암호화(평문→암호문)한 행 수
        long remainingPlaintext,  // 실행 후 남은 평문 잔존 행 수 (raw 컬럼 enc:v1 접두 없음)
        int pagesCommitted,       // 커밋된 페이지 수 (페이지 단위 커밋)
        boolean complete,         // remainingPlaintext == 0
        List<String> notes
) {

    /** 백필 실행 결과. */
    public static PayoutPiiBackfillReport of(int pageSize, long backfilled,
                                             long remainingPlaintext, int pagesCommitted) {
        List<String> notes = new ArrayList<>();
        notes.add("재암호화 " + backfilled + "건 / " + pagesCommitted + "페이지 커밋 (페이지 크기 " + pageSize + ")");
        if (remainingPlaintext > 0) {
            notes.add("평문 잔존 " + remainingPlaintext + "건 — 안전 상한에 걸렸거나 실행 중 신규 유입. 재실행 권장");
        } else {
            notes.add("평문 잔존 0 — 전체 암호문 전환 완료");
        }
        return new PayoutPiiBackfillReport(pageSize, backfilled, remainingPlaintext,
                pagesCommitted, remainingPlaintext == 0, List.copyOf(notes));
    }

    /** 백필 실행 없이 평문 잔존 건수만 조회하는 검증 응답. */
    public static PayoutPiiBackfillReport status(long remainingPlaintext) {
        List<String> notes = new ArrayList<>();
        notes.add(remainingPlaintext > 0
                ? "평문 잔존 " + remainingPlaintext + "건 — 재암호화 백필 대상"
                : "평문 잔존 0 — 재암호화 불필요");
        return new PayoutPiiBackfillReport(0, 0, remainingPlaintext, 0,
                remainingPlaintext == 0, List.copyOf(notes));
    }
}
