package github.lms.lemuel.integrity.domain;

/**
 * 백필 실행 결과 요약 (INV-5 역분개 누락 / INV-6 Payout 미생성 정정용).
 *
 * <ul>
 *   <li>{@code created} — 이번 실행이 새로 만든 Payout·역분개 건수</li>
 *   <li>{@code skipped} — 후보였지만 이번 실행이 정정하지 못한 건수(판매자 미해석·0원·이미 존재)</li>
 *   <li>{@code remaining} — 실행 종료 시점 탐지에 남아 있는 후보 건수(탐지 상한 절단 기준의 하한값)</li>
 *   <li>{@code pagesProcessed} — 처리한 탐지 페이지 수 (dry run 은 0)</li>
 * </ul>
 */
public record BackfillReport(int created, int skipped, int remaining, int pagesProcessed, boolean complete) {

    public static BackfillReport of(int created, int skipped, int remaining, int pagesProcessed) {
        return new BackfillReport(created, skipped, remaining, pagesProcessed, remaining == 0);
    }
}
