package github.lms.lemuel.investment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NewsArticleSummary — 기사가 스스로 답하는 "이 키워드를 언급하는가"(제목·요약, 본문 없음) 검증.
 */
class NewsArticleSummaryTest {

    private static NewsArticleSummary article(String title, String summary) {
        return new NewsArticleSummary(title, summary, "https://news.example/1",
                Instant.parse("2026-07-10T00:00:00Z"));
    }

    @Test
    @DisplayName("제목에 키워드가 있으면 mentions 참")
    void mentionsInTitle() {
        assertThat(article("A사 유상증자 결정", "요약").mentions("유상증자")).isTrue();
    }

    @Test
    @DisplayName("요약에만 키워드가 있어도 mentions 참")
    void mentionsInSummary() {
        assertThat(article("A사 공시", "거래정지 예고").mentions("거래정지")).isTrue();
    }

    @Test
    @DisplayName("제목·요약 어디에도 없으면 mentions 거짓")
    void notMentioned() {
        assertThat(article("A사 신제품 출시", "영업이익 증가").mentions("횡령")).isFalse();
    }

    @Test
    @DisplayName("요약이 null 이어도 NPE 없이 제목만으로 판정한다")
    void nullSummaryIsSafe() {
        assertThat(article("A사 배임 혐의", null).mentions("배임")).isTrue();
        assertThat(article("A사 신규 채용", null).mentions("소송")).isFalse();
    }
}
