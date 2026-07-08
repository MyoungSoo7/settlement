package github.lms.lemuel.company.adapter.out.analysis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Google Gemini(Generative Language API) 감성분석 연동 설정 (ADR 0023 Phase 4).
 *
 * @param apiKey    developers.google 발급 키(x-goog-api-key) — 미설정이면 키워드 폴백
 * @param model     기본 gemini-2.5-flash (분류기라 저비용 flash). 설정으로 교체 가능
 * @param baseUrl   기본 https://generativelanguage.googleapis.com
 * @param maxTokens generationConfig.maxOutputTokens — 2.5 계열은 thinking 이 예산을 먹을 수 있어 여유를 둔다
 */
@ConfigurationProperties(prefix = "app.company.sentiment.gemini")
public record GeminiSentimentProperties(String apiKey, String model, String baseUrl, int maxTokens) {

    public GeminiSentimentProperties {
        if (model == null || model.isBlank()) {
            model = "gemini-2.5-flash";
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://generativelanguage.googleapis.com";
        }
        if (maxTokens <= 0) {
            maxTokens = 256;
        }
        if (apiKey == null) {
            apiKey = "";
        }
    }

    public boolean configured() {
        return !apiKey.isBlank();
    }
}
