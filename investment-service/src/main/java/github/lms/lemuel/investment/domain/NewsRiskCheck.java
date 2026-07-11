package github.lms.lemuel.investment.domain;

import java.time.Instant;
import java.util.List;

/**
 * 악재 뉴스 체크(R3) 결과 — 최근 기사 제목·요약에서 악재 키워드가 확인되는지.
 *
 * <p>"스캔했는데 악재가 없음"(CLEAR)도 유효한 결과다 — 없으면 없다고 말할 근거가 된다.
 * NO_DATA(기업 미등록)는 악재 없음이 아니라 판단 불가로 구분한다.
 */
public record NewsRiskCheck(Status status, int scannedCount, List<Flag> flags) {

    public enum Status {
        /** 스캔 완료 — 악재 키워드 미검출. */
        CLEAR,
        /** 악재 키워드 검출 — flags 확인 필요. */
        FLAGGED,
        /** company-service 에 기업 미등록 — 판단 불가(악재 없음이 아님). */
        NO_DATA,
        /** 뉴스 원천 호출 실패 — 이 축만 강등. */
        UNAVAILABLE
    }

    /** 악재 플래그 1건 — 어떤 키워드가 어느 기사에서 확인됐는지. */
    public record Flag(String keyword, String title, String url, Instant publishedAt) {
    }

    public static NewsRiskCheck of(int scannedCount, List<Flag> flags) {
        return new NewsRiskCheck(flags.isEmpty() ? Status.CLEAR : Status.FLAGGED,
                scannedCount, List.copyOf(flags));
    }

    public static NewsRiskCheck noData() {
        return new NewsRiskCheck(Status.NO_DATA, 0, List.of());
    }

    public static NewsRiskCheck unavailable() {
        return new NewsRiskCheck(Status.UNAVAILABLE, 0, List.of());
    }
}
