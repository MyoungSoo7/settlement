package github.lms.lemuel.investment.adapter.out.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.investment.application.port.out.LoadCompanyNewsPort;
import github.lms.lemuel.investment.domain.NewsArticleSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * company-service 공개 API HTTP 클라이언트 — 기업 존재 확인 후 최근 기사(제목·요약·링크)를 조회한다.
 *
 * <ul>
 *   <li>{@code GET {base}/api/company/companies/{stockCode}} → 기업 존재 확인(404 → empty Optional)</li>
 *   <li>{@code GET {base}/api/company/companies/{stockCode}/articles?page=0&size=50} → 최근 기사 페이지</li>
 * </ul>
 *
 * <p>악재 스캔 입력이므로 최신 1페이지(50건)면 충분하다. 조회 결과는 10분 캐시된다.
 */
@Component
public class CompanyNewsApiClient implements LoadCompanyNewsPort {

    private static final Logger log = LoggerFactory.getLogger(CompanyNewsApiClient.class);
    private static final int PAGE_SIZE = 50;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public CompanyNewsApiClient(RestClient.Builder restClientBuilder,
                                ObjectMapper objectMapper,
                                @Value("${app.company.base-url:http://localhost:8090}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
    }

    @Override
    @Cacheable(cacheNames = "beginnerNewsFeed", key = "#stockCode")
    public Optional<List<NewsArticleSummary>> loadRecentArticles(String stockCode) {
        JsonNode company = getJson("/api/company/companies/" + stockCode);
        if (company == null) {
            return Optional.empty(); // 기업 미등록 — "기사 0건"과 구분
        }
        JsonNode page = getJson("/api/company/companies/" + stockCode
                + "/articles?page=0&size=" + PAGE_SIZE);
        List<NewsArticleSummary> articles = new ArrayList<>();
        if (page != null) {
            for (JsonNode item : page.path("content")) {
                articles.add(new NewsArticleSummary(
                        item.path("title").asText(""),
                        item.path("summary").asText(""),
                        item.path("url").asText(null),
                        parseInstant(item.get("publishedAt"))));
            }
        }
        return Optional.of(articles);
    }

    /** GET → JsonNode. 404 는 null(미존재), 그 외 오류는 예외. */
    private JsonNode getJson(String path) {
        try {
            String body = restClient.get().uri(path).retrieve().body(String.class);
            return body == null ? null : objectMapper.readTree(body);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return null;
            }
            throw new IllegalStateException("company API 오류 path=" + path + " status=" + e.getStatusCode(), e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("company API 응답 파싱 실패 path=" + path, e);
        }
    }

    private static Instant parseInstant(JsonNode node) {
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        try {
            return Instant.parse(node.asText());
        } catch (DateTimeParseException e) {
            log.warn("company publishedAt 파싱 실패, null 처리: raw={}", node.asText());
            return null;
        }
    }
}
