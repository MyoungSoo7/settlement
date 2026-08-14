package github.lms.lemuel.tax.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 세금계산서 OCR 설정 (prefix=app.tax.ocr).
 *
 * @param provider        {@code text-layer}(기본, 오프라인 결정적 파서) 또는 {@code gemini}(AI 비전 OCR)
 * @param apiKey          Gemini 키(x-goog-api-key). 미설정이면 gemini 프로바이더는 미구성 상태 → 503
 * @param model           기본 gemini-2.5-flash
 * @param baseUrl         기본 https://generativelanguage.googleapis.com
 * @param maxOutputTokens generationConfig.maxOutputTokens (기본 1024 — 추출 JSON 은 짧다)
 */
@ConfigurationProperties(prefix = "app.tax.ocr")
public record TaxOcrProperties(String provider, String apiKey, String model, String baseUrl,
                               Integer maxOutputTokens) {

    public TaxOcrProperties {
        if (provider == null || provider.isBlank()) {
            provider = "text-layer";
        }
        if (apiKey == null) {
            apiKey = "";
        }
        if (model == null || model.isBlank()) {
            model = "gemini-2.5-flash";
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://generativelanguage.googleapis.com";
        }
        if (maxOutputTokens == null || maxOutputTokens <= 0) {
            maxOutputTokens = 1024;
        }
    }

    public boolean configured() {
        return !apiKey.isBlank();
    }
}
