package github.lms.lemuel.ai.chat.adapter.out.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Google Gemini(Generative Language API) 채팅 연동 설정 (prefix=app.ai.gemini).
 * company-service 의 GeminiSentimentProperties 와 동일한 형태 — 검증된 패턴 재사용.
 *
 * @param apiKey    developers.google 발급 키(x-goog-api-key) — 미설정이면 채팅 API 503
 * @param model     기본 gemini-2.5-flash (설정으로 교체 가능)
 * @param baseUrl   기본 https://generativelanguage.googleapis.com
 * @param maxTokens generationConfig.maxOutputTokens — 2.5 계열은 thinking 이 예산을 먹으므로 넉넉히(기본 2048)
 */
@ConfigurationProperties(prefix = "app.ai.gemini")
public record GeminiChatProperties(String apiKey, String model, String baseUrl, int maxTokens) {

    public GeminiChatProperties {
        if (model == null || model.isBlank()) {
            model = "gemini-2.5-flash";
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://generativelanguage.googleapis.com";
        }
        if (maxTokens <= 0) {
            maxTokens = 2048;
        }
        if (apiKey == null) {
            apiKey = "";
        }
    }

    public boolean configured() {
        return !apiKey.isBlank();
    }
}
