package github.lms.lemuel.company.adapter.out.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.company.application.port.out.AnalyzeSentimentPort;
import github.lms.lemuel.company.domain.ArticleSentiment;
import github.lms.lemuel.company.domain.IssueCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * LLM(Claude) 기반 감성 분석기 (ADR 0023 Phase 4).
 *
 * <p>{@code app.company.sentiment.provider=llm} 일 때만 등록되며, {@link AnalyzeSentimentPort} 뒤에서
 * 룰 기반 {@link KeywordSentimentAnalyzer} 를 대체한다(Phase 2 에서 이 교체를 위해 포트를 뒀다).
 * 키 미설정·호출 실패·응답 파싱 실패 시 <b>키워드 분석기로 폴백</b>한다 — LLM 장애가 평판 산정을 막지 않는다.
 *
 * <p>Anthropic Messages API 를 프로젝트 표준인 Spring {@link RestClient} 로 직접 호출한다(무거운 벤더 SDK
 * 미도입 — Naver/DART 연동과 동일 패턴). 모델은 기본 claude-opus-4-8, 설정으로 교체 가능.
 */
@Component
@ConditionalOnProperty(name = "app.company.sentiment.provider", havingValue = "llm")
public class LlmSentimentAnalyzer implements AnalyzeSentimentPort {

    private static final Logger log = LoggerFactory.getLogger(LlmSentimentAnalyzer.class);

    private static final String SYSTEM_PROMPT = """
            너는 한국 기업 뉴스 헤드라인의 평판 영향을 분류하는 분류기다.
            입력(제목/요약)에 대해 아래 라벨 중 정확히 하나만, 다른 말 없이 출력해라:
            POSITIVE, NEUTRAL, NEGATIVE_FINANCIAL, NEGATIVE_LEGAL, NEGATIVE_GOVERNANCE,
            NEGATIVE_LABOR, NEGATIVE_PRODUCT, NEGATIVE_OTHER
            부정 기사는 가장 핵심적인 이슈 카테고리를 골라라(재무/법률/지배구조/노동/제품). 애매하면 NEGATIVE_OTHER.""";

    private final ClaudeSentimentProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final KeywordSentimentAnalyzer fallback = new KeywordSentimentAnalyzer();

    public LlmSentimentAnalyzer(ClaudeSentimentProperties properties,
                                RestClient.Builder restClientBuilder,
                                ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClientBuilder.baseUrl(properties.baseUrl()).build();
        this.objectMapper = objectMapper;
        if (!properties.configured()) {
            log.warn("app.company.sentiment.claude.api-key 미설정 — provider=llm 이지만 키워드 분석기로 폴백");
        }
    }

    @Override
    public ArticleSentiment analyze(String title, String summary) {
        if (!properties.configured()) {
            return fallback.analyze(title, summary);
        }
        try {
            String label = requestLabel(title, summary);
            return toSentiment(label);
        } catch (RuntimeException e) {
            log.warn("LLM 감성분석 실패 — 키워드 폴백. reason={}", e.getMessage());
            return fallback.analyze(title, summary);
        }
    }

    private String requestLabel(String title, String summary) {
        String userText = "제목: " + (title == null ? "" : title)
                + "\n요약: " + (summary == null ? "" : summary);
        Map<String, Object> body = Map.of(
                "model", properties.model(),
                "max_tokens", properties.maxTokens(),
                "system", SYSTEM_PROMPT,
                "messages", List.of(Map.of("role", "user", "content", userText)));
        String response = restClient.post()
                .uri("/v1/messages")
                .header("x-api-key", properties.apiKey())
                .header("anthropic-version", properties.version())
                .header("content-type", "application/json")
                .body(body)
                .retrieve()
                .body(String.class);
        return extractText(response);
    }

    private String extractText(String response) {
        try {
            JsonNode root = objectMapper.readTree(response == null ? "{}" : response);
            // 안전 가드: refusal/비정상 응답이면 content[0].text 가 없을 수 있다 → 폴백 유도
            JsonNode content = root.path("content");
            if (!content.isArray() || content.isEmpty()) {
                throw new IllegalStateException("빈 content (stop_reason=" + root.path("stop_reason").asText() + ")");
            }
            return content.get(0).path("text").asText("");
        } catch (Exception e) {
            throw new IllegalStateException("Anthropic 응답 파싱 실패: " + e.getMessage(), e);
        }
    }

    /** 라벨 문자열 → 도메인 감성. 알 수 없는 라벨은 NEUTRAL(보수적). */
    static ArticleSentiment toSentiment(String rawLabel) {
        String label = rawLabel == null ? "" : rawLabel.trim().toUpperCase();
        return switch (label) {
            case "POSITIVE" -> ArticleSentiment.positive();
            case "NEGATIVE_FINANCIAL" -> ArticleSentiment.negative(IssueCategory.FINANCIAL);
            case "NEGATIVE_LEGAL" -> ArticleSentiment.negative(IssueCategory.LEGAL);
            case "NEGATIVE_GOVERNANCE" -> ArticleSentiment.negative(IssueCategory.GOVERNANCE);
            case "NEGATIVE_LABOR" -> ArticleSentiment.negative(IssueCategory.LABOR);
            case "NEGATIVE_PRODUCT" -> ArticleSentiment.negative(IssueCategory.PRODUCT);
            case "NEGATIVE_OTHER" -> ArticleSentiment.negative(null);
            default -> ArticleSentiment.neutral();
        };
    }
}
