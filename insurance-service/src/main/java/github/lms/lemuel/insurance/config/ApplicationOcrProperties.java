package github.lms.lemuel.insurance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * 청약서류 OCR 설정 (prefix=app.insurance.application-ocr — card {@code ReceiptOcrProperties} 와 동형).
 *
 * @param apiKey          Gemini 키(x-goog-api-key). 미설정이면 서류 첨부는 503 (무폴백, ADR 0036)
 * @param model           기본 gemini-2.5-flash
 * @param baseUrl         기본 https://generativelanguage.googleapis.com
 * @param maxOutputTokens generationConfig.maxOutputTokens (기본 1024 — 추출 JSON 은 짧다)
 * @param reviewThreshold 판독 신뢰도 리뷰 임계 (기본 0.80) — 미만이면 값 일치와 무관하게 NEEDS_REVIEW
 * @param required        전면 강제 플래그 (기본 false) — 켜면 청약서류 미첨부 청약은 승인 자체가
 *                        거절된다(422). 끄면 점진 도입(첨부된 경우에만 MATCHED 요구)
 */
@ConfigurationProperties(prefix = "app.insurance.application-ocr")
public record ApplicationOcrProperties(String apiKey, String model, String baseUrl,
                                       Integer maxOutputTokens, BigDecimal reviewThreshold,
                                       Boolean required) {

    public ApplicationOcrProperties {
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
        if (required == null) {
            required = Boolean.FALSE;
        }
    }
}
