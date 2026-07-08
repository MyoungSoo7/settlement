package github.lms.lemuel.company.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 기업 뉴스 기사 — 메타데이터만 보유한다.
 *
 * <p>★ 저작권 제약(ADR 0023): 기사 <b>본문 전문을 저장하지 않는다</b>. 제목·요약(발췌)·언론사·
 * 발행일시·원문 URL 이 전부다.
 *
 * <p>동일성은 {@code urlHash}(정규화한 원문 URL 의 SHA-256) — 재수집·중복 수집 멱등 키이며
 * DB 의 {@code articles.url_hash UNIQUE} 가 최종 방어선이다.
 */
public class Article {

    static final int MAX_TITLE_LENGTH = 500;
    static final int MAX_SUMMARY_LENGTH = 2000;

    private final String urlHash;
    private final String stockCode;
    private final ArticleSource source;
    private final String title;
    private final String summary;
    private final String publisher;
    private final String url;
    private final Instant publishedAt;

    private Article(String urlHash, String stockCode, ArticleSource source, String title,
                    String summary, String publisher, String url, Instant publishedAt) {
        this.urlHash = urlHash;
        this.stockCode = stockCode;
        this.source = source;
        this.title = title;
        this.summary = summary;
        this.publisher = publisher;
        this.url = url;
        this.publishedAt = publishedAt;
    }

    /** 수집 시 생성 — URL 을 정규화해 urlHash 를 계산한다. */
    public static Article collect(String stockCode, ArticleSource source, String title,
                                  String summary, String publisher, String url, Instant publishedAt) {
        if (stockCode == null || stockCode.length() != 6) {
            throw new IllegalArgumentException("종목코드는 6자리여야 합니다: " + stockCode);
        }
        if (source == null) {
            throw new IllegalArgumentException("수집원(source)은 필수입니다");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("기사 제목은 필수입니다");
        }
        String normalizedUrl = normalizeUrl(url);
        return new Article(
                sha256(normalizedUrl),
                stockCode,
                source,
                truncate(title.strip(), MAX_TITLE_LENGTH),
                summary == null || summary.isBlank() ? null : truncate(summary.strip(), MAX_SUMMARY_LENGTH),
                publisher == null || publisher.isBlank() ? null : publisher.strip(),
                normalizedUrl,
                publishedAt);
    }

    /** 영속 계층 재구성용 — 저장 당시 계산된 urlHash 를 그대로 신뢰한다. */
    public static Article rehydrate(String urlHash, String stockCode, ArticleSource source, String title,
                                    String summary, String publisher, String url, Instant publishedAt) {
        return new Article(urlHash, stockCode, source, title, summary, publisher, url, publishedAt);
    }

    /**
     * URL 정규화 — 같은 기사가 fragment/공백 차이로 다른 멱등 키가 되지 않게 한다.
     * (트래킹 파라미터 제거 등 더 공격적인 정규화는 오탐 위험이 있어 하지 않는다.)
     */
    static String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("원문 URL 은 필수입니다");
        }
        String normalized = url.strip();
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            throw new IllegalArgumentException("원문 URL 은 http(s) 여야 합니다: " + url);
        }
        int fragment = normalized.indexOf('#');
        if (fragment >= 0) {
            normalized = normalized.substring(0, fragment);
        }
        return normalized;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원 JVM", e);
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    public String urlHash() {
        return urlHash;
    }

    public String stockCode() {
        return stockCode;
    }

    public ArticleSource source() {
        return source;
    }

    public String title() {
        return title;
    }

    public String summary() {
        return summary;
    }

    public String publisher() {
        return publisher;
    }

    public String url() {
        return url;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Article other && urlHash.equals(other.urlHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(urlHash);
    }
}
