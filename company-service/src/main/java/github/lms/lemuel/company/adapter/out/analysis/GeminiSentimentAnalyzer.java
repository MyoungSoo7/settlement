package github.lms.lemuel.company.adapter.out.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.company.application.port.out.AnalyzeSentimentPort;
import github.lms.lemuel.company.domain.ArticleSentiment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Google Gemini 기반 감성 분석기 (ADR 0023 Phase 4).
 *
 * <p>{@code app.company.sentiment.provider=gemini} 일 때만 등록되며 {@link AnalyzeSentimentPort} 뒤에서
 * 룰 기반 분석기를 대체한다. 키 미설정·호출/파싱 실패 시 {@link KeywordSentimentAnalyzer} 로 폴백한다
 * (Claude 구현체와 동일한 fail-open 철학 — LLM 장애가 평판 산정을 막지 않는다).
 *
 * <p>Generative Language API 를 프로젝트 표준 Spring {@link RestClient} 로 직접 호출한다(벤더 SDK 미도입).
 * 분류 지시·라벨 매핑은 Claude 구현체와 {@link SentimentLabels} 로 공유한다.
 */
@Component
@ConditionalOnProperty(name = "app.company.sentiment.provider", havingValue = "gemini")
public class GeminiSentimentAnalyzer implements AnalyzeSentimentPort {

    private static final Logger log = LoggerFactory.getLogger(GeminiSentimentAnalyzer.class);

    private final GeminiSentimentProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final KeywordSentimentAnalyzer fallback = new KeywordSentimentAnalyzer();

    public GeminiSentimentAnalyzer(GeminiSentimentProperties properties,
                                   RestClient.Builder restClientBuilder,
                                   ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClientBuilder.baseUrl(properties.baseUrl()).build();
        this.objectMapper = objectMapper;
        if (!properties.configured()) {
            log.warn("app.company.sentiment.gemini.api-key 미설정 — provider=gemini 이지만 키워드 분석기로 폴백");
        }
    }

    @Override
    public ArticleSentiment analyze(String title, String summary) {
        if (!properties.configured()) {
            return fallback.analyze(title, summary);
        }
        try {
            return SentimentLabels.toSentiment(requestLabel(title, summary));
        } catch (RuntimeException e) {
            log.warn("Gemini 감성분석 실패 — 키워드 폴백. reason={}", e.getMessage());
            return fallback.analyze(title, summary);
        }
    }

    private String requestLabel(String title, String summary) {
        // Generative Language API: system_instruction + contents[user].parts[text]
        Map<String, Object> body = Map.of(
                "system_instruction", Map.of("parts", List.of(Map.of("text", SentimentLabels.INSTRUCTION))),
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("text", SentimentLabels.userText(title, summary))))),
                "generationConfig", Map.of("maxOutputTokens", properties.maxTokens()));
        String response = restClient.post()
                .uri("/v1beta/models/{model}:generateContent", properties.model())
                .header("x-goog-api-key", properties.apiKey())
                .header("content-type", "application/json")
                .body(body)
                .retrieve()
                .body(String.class);
        return extractText(response);
    }

    private String extractText(String response) {
        try {
            JsonNode root = objectMapper.readTree(response == null ? "{}" : response);
            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                throw new IllegalStateException("빈 candidates (promptFeedback="
                        + root.path("promptFeedback") + ")");
            }
            JsonNode parts = candidates.get(0).path("content").path("parts");
            if (!parts.isArray() || parts.isEmpty()) {
                throw new IllegalStateException("빈 parts (finishReason="
                        + candidates.get(0).path("finishReason").asText() + ")");
            }
            return parts.get(0).path("text").asText("");
        } catch (Exception e) {
            throw new IllegalStateException("Gemini 응답 파싱 실패: " + e.getMessage(), e);
        }
    }
}
