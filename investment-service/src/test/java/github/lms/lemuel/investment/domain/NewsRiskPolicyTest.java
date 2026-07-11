package github.lms.lemuel.investment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NewsRiskPolicy — 악재 키워드(유상증자·횡령·배임·거래정지·상장폐지·소송) 제목·요약 스캔 검증.
 */
class NewsRiskPolicyTest {

    private final NewsRiskPolicy policy = new NewsRiskPolicy();

    private static NewsArticleSummary article(String title, String summary) {
        return new NewsArticleSummary(title, summary, "https://news.example/1",
                Instant.parse("2026-07-10T00:00:00Z"));
    }

    @Test
    @DisplayName("기사 0건 스캔도 유효한 결과다 — CLEAR")
    void emptyArticlesIsClear() {
        NewsRiskCheck check = policy.scan(List.of());

        assertThat(check.status()).isEqualTo(NewsRiskCheck.Status.CLEAR);
        assertThat(check.scannedCount()).isZero();
        assertThat(check.flags()).isEmpty();
    }

    @Test
    @DisplayName("제목에 악재 키워드가 있으면 FLAGGED")
    void flagsKeywordInTitle() {
        NewsRiskCheck check = policy.scan(List.of(
                article("A사 대규모 유상증자 결정", "요약"),
                article("A사 신제품 출시", "요약")));

        assertThat(check.status()).isEqualTo(NewsRiskCheck.Status.FLAGGED);
        assertThat(check.scannedCount()).isEqualTo(2);
        assertThat(check.flags()).hasSize(1);
        assertThat(check.flags().get(0).keyword()).isEqualTo("유상증자");
        assertThat(check.flags().get(0).title()).contains("유상증자");
    }

    @Test
    @DisplayName("요약에만 키워드가 있어도 플래그된다")
    void flagsKeywordInSummary() {
        NewsRiskCheck check = policy.scan(List.of(
                article("A사 관련 공시", "한국거래소가 거래정지를 예고했다")));

        assertThat(check.status()).isEqualTo(NewsRiskCheck.Status.FLAGGED);
        assertThat(check.flags().get(0).keyword()).isEqualTo("거래정지");
    }

    @Test
    @DisplayName("한 기사에 키워드가 여러 개면 최초 매칭 1개만 플래그(기사당 1플래그)")
    void onePerArticle() {
        NewsRiskCheck check = policy.scan(List.of(
                article("전 대표 횡령·배임 혐의 기소", "요약")));

        assertThat(check.flags()).hasSize(1);
        assertThat(check.flags().get(0).keyword()).isEqualTo("횡령");
    }

    @Test
    @DisplayName("악재 키워드가 없으면 CLEAR — 없으면 없다고 말할 근거")
    void clearWhenNoRiskKeyword() {
        NewsRiskCheck check = policy.scan(List.of(
                article("A사 2분기 실적 발표", "영업이익 증가"),
                article("A사 신규 채용 확대", null)));

        assertThat(check.status()).isEqualTo(NewsRiskCheck.Status.CLEAR);
        assertThat(check.scannedCount()).isEqualTo(2);
    }
}
