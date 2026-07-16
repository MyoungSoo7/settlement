package github.lms.lemuel.company.adapter.out.analysis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Google Gemini(Generative Language API) 감성분석 연동 설정 (ADR 0023 Phase 4).
 *
 * @param apiKey        developers.google 발급 키(x-goog-api-key) — 미설정이면 키워드 폴백
 * @param model         기본 gemini-2.5-flash (분류기라 저비용 flash). 설정으로 교체 가능
 * @param baseUrl       기본 https://generativelanguage.googleapis.com
 * @param maxTokens     generationConfig.maxOutputTokens — 2.5 계열은 thinking 이 예산을 먹을 수 있어 여유를 둔다
 * @param dailyQuota    일일 Gemini 호출 상한(무료티어 초과·과금 방지) — 도달 시 키워드 폴백. 기본 200(&le;0 이면 기본)
 * @param minIntervalMs 호출 간 최소 간격(ms) — 분당 상한(RPM) 보호 스로틀. 기본 0(스로틀 없음, 음수면 0)
 */
@ConfigurationProperties(prefix = "app.company.sentiment.gemini")
public record GeminiSentimentProperties(String apiKey, String model, String baseUrl, int maxTokens,
                                        int dailyQuota, long minIntervalMs) {

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
        if (dailyQuota <= 0) {
            dailyQuota = 200;
        }
        if (minIntervalMs < 0) {
            minIntervalMs = 0;
        }
    }

    public boolean configured() {
        return !apiKey.isBlank();
    }
}
