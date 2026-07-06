package github.lms.lemuel.company.adapter.out.analysis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Claude(Anthropic) 감성분석 연동 설정 (ADR 0023 Phase 4).
 *
 * @param apiKey     Anthropic API 키(x-api-key) — 미설정이면 LLM 분석 비활성(키워드 폴백)
 * @param model      기본 claude-opus-4-8 (고정 규칙: 사용자가 바꾸지 않는 한 opus-4-8)
 * @param baseUrl    기본 https://api.anthropic.com
 * @param version    anthropic-version 헤더 (기본 2023-06-01)
 * @param maxTokens  응답 상한 — 라벨만 받으므로 작게
 */
@ConfigurationProperties(prefix = "app.company.sentiment.claude")
public record ClaudeSentimentProperties(String apiKey, String model, String baseUrl,
                                        String version, int maxTokens) {

    public ClaudeSentimentProperties {
        if (model == null || model.isBlank()) {
            model = "claude-opus-4-8";
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.anthropic.com";
        }
        if (version == null || version.isBlank()) {
            version = "2023-06-01";
        }
        if (maxTokens <= 0) {
            maxTokens = 20;
        }
        if (apiKey == null) {
            apiKey = "";
        }
    }

    public boolean configured() {
        return !apiKey.isBlank();
    }
}
