package github.lms.lemuel.company.adapter.out.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.company.application.port.out.NewsClientPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 네이버 뉴스 검색 API 클라이언트 (GET /v1/search/news.json).
 *
 * <p>wire 포맷 정리를 여기서 끝낸다 — title/description 의 검색어 하이라이트(&lt;b&gt;)와
 * HTML 엔티티를 제거하고, pubDate(RFC 1123)를 Instant 로 파싱한다. 네이버 응답에는 언론사명이
 * 없어 originallink 호스트를 publisher 로 쓴다.
 */
@Component
public class NaverNewsApiClient implements NewsClientPort {

    private static final Logger log = LoggerFactory.getLogger(NaverNewsApiClient.class);
    private static final DateTimeFormatter PUB_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private final NaverNewsProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public NaverNewsApiClient(NaverNewsProperties properties, RestClient.Builder restClientBuilder,
                              ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClientBuilder.baseUrl(properties.baseUrl()).build();
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean isConfigured() {
        return properties.configured();
    }

    @Override
    public List<NewsItem> fetchNews(String companyName) {
        String body = restClient.get()
                .uri(uri -> uri.path("/v1/search/news.json")
                        .queryParam("query", companyName)
                        .queryParam("display", properties.display())
                        .queryParam("sort", "date")
                        .build())
                .header("X-Naver-Client-Id", properties.clientId())
                .header("X-Naver-Client-Secret", properties.clientSecret())
                .retrieve()
                .body(String.class);
        return parse(body);
    }

    private List<NewsItem> parse(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body == null ? "{}" : body);
        } catch (Exception e) {
            throw new IllegalStateException("네이버 뉴스 응답 JSON 파싱 실패", e);
        }
        if (root.has("errorCode")) {
            throw new IllegalStateException("네이버 뉴스 API 오류 errorCode=%s message=%s"
                    .formatted(root.path("errorCode").asText(), root.path("errorMessage").asText()));
        }
        List<NewsItem> items = new ArrayList<>();
        for (JsonNode item : root.path("items")) {
            String originalLink = item.path("originallink").asText("");
            String link = item.path("link").asText("");
            String url = originalLink.isBlank() ? link : originalLink;
            items.add(new NewsItem(
                    cleanText(item.path("title").asText("")),
                    cleanText(item.path("description").asText("")),
                    hostOf(url),
                    url,
                    parsePubDate(item.path("pubDate").asText(""))));
        }
        return items;
    }

    /** 검색어 하이라이트 태그(<b>)·HTML 엔티티 제거. */
    static String cleanText(String raw) {
        return raw.replaceAll("<[^>]*>", "")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .strip();
    }

    static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null || host.isBlank() ? null : host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Instant parsePubDate(String raw) {
        if (raw.isBlank()) {
            return null;
        }
        try {
            return Instant.from(PUB_DATE.parse(raw));
        } catch (DateTimeParseException e) {
            log.warn("pubDate 파싱 실패, null 처리: {}", raw);
            return null;
        }
    }
}
