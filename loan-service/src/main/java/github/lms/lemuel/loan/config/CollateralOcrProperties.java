package github.lms.lemuel.loan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * 담보서류 OCR 설정 (prefix=app.loan.collateral-ocr — card·insurance OCR 프로퍼티와 동형).
 *
 * @param apiKey          Gemini 키(x-goog-api-key). 미설정이면 서류 첨부는 503 (무폴백, ADR 0036)
 * @param model           기본 gemini-2.5-flash
 * @param baseUrl         기본 https://generativelanguage.googleapis.com
 * @param maxOutputTokens generationConfig.maxOutputTokens (기본 1024 — 추출 JSON 은 짧다)
 * @param reviewThreshold 판독 신뢰도 리뷰 임계 (기본 0.80) — 미만이면 값 일치와 무관하게 NEEDS_REVIEW
 */
@ConfigurationProperties(prefix = "app.loan.collateral-ocr")
public record CollateralOcrProperties(String apiKey, String model, String baseUrl,
                                      Integer maxOutputTokens, BigDecimal reviewThreshold) {

    public CollateralOcrProperties {
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
        if (reviewThreshold == null || reviewThreshold.signum() < 0
                || reviewThreshold.compareTo(BigDecimal.ONE) > 0) {
            reviewThreshold = new BigDecimal("0.80");
        }
    }
}
