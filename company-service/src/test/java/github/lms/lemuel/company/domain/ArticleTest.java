package github.lms.lemuel.company.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArticleTest {

    private static Article article(String url) {
        return Article.collect("005930", ArticleSource.NAVER_NEWS, "삼성전자 실적 발표",
                "요약", "news.example.com", url, Instant.parse("2026-07-06T00:00:00Z"));
    }

    @Test
    @DisplayName("urlHash 는 정규화된 URL 의 SHA-256 hex 64자")
    void computesUrlHash() {
        Article article = article("https://news.example.com/a/1");
        assertEquals(64, article.urlHash().length());
    }

    @Test
    @DisplayName("fragment·공백 차이는 같은 멱등 키가 된다")
    void normalizesUrlForIdempotencyKey() {
        assertEquals(article("https://news.example.com/a/1").urlHash(),
                article("  https://news.example.com/a/1#comments ").urlHash());
        assertNotEquals(article("https://news.example.com/a/1").urlHash(),
                article("https://news.example.com/a/2").urlHash());
    }

    @Test
    @DisplayName("동일성은 urlHash 기준 — 재수집된 같은 기사는 같다")
    void equalityByUrlHash() {
        assertEquals(article("https://news.example.com/a/1"), article("https://news.example.com/a/1#x"));
    }

    @Test
    @DisplayName("http(s) 가 아닌 URL·빈 제목·잘못된 종목코드는 거부한다")
    void rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> article("ftp://news.example.com/a"));
        assertThrows(IllegalArgumentException.class, () -> article(" "));
        assertThrows(IllegalArgumentException.class, () -> Article.collect("005930", ArticleSource.NAVER_NEWS,
                " ", null, null, "https://a.b/c", null));
        assertThrows(IllegalArgumentException.class, () -> Article.collect("59300", ArticleSource.NAVER_NEWS,
                "제목", null, null, "https://a.b/c", null));
        assertThrows(IllegalArgumentException.class, () -> Article.collect("005930", null,
                "제목", null, null, "https://a.b/c", null));
    }

    @Test
    @DisplayName("제목·요약은 최대 길이로 잘리고, 빈 요약/언론사는 null 처리")
    void truncatesAndNormalizesOptionalFields() {
        String longTitle = "가".repeat(600);
        Article article = Article.collect("005930", ArticleSource.NAVER_NEWS, longTitle,
                " ", " ", "https://a.b/c", null);
        assertEquals(500, article.title().length());
        assertNull(article.summary());
        assertNull(article.publisher());
    }
}
