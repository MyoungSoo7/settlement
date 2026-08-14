package github.lms.lemuel.deposit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * 예치금 증빙 OCR 설정 (prefix=app.deposit.proof-ocr — card·loan·insurance OCR 프로퍼티와 동형).
 *
 * @param apiKey            Gemini 키(x-goog-api-key). 미설정이면 증빙 첨부는 503 (무폴백, ADR 0036)
 * @param model             기본 gemini-2.5-flash
 * @param baseUrl           기본 https://generativelanguage.googleapis.com
 * @param maxOutputTokens   generationConfig.maxOutputTokens (기본 1024 — 추출 JSON 은 짧다)
 * @param reviewThreshold   판독 신뢰도 리뷰 임계 (기본 0.80) — 미만이면 값 일치와 무관하게 NEEDS_REVIEW
 * @param dateToleranceDays 이체일 허용 리드타임 (기본 3일) — 은행 이체 후 운영자가 며칠 뒤 수기
 *                          기표하는 정상 업무를 흡수한다. 다른 확산처(±1일)와 다른 이유는
 *                          {@code DepositProofMatcher} javadoc 참조
 * @param required          전면 강제 플래그 (기본 false) — 켜면 증빙 미첨부 수기 기표가 거절된다(422).
 *                          면제 referenceType(아래)에는 적용되지 않는다
 * @param requiredExemptReferenceTypes 전면 강제 면제 referenceType (기본 SETTLEMENT·PAYOUT) —
 *                          Kafka 자동 기표는 이벤트가 정본이라 사람 증빙 대상이 아니다. 이 면제가
 *                          없으면 required=true 가 자동 정산 입금·지급 차감을 전부 멈춘다
 */
@ConfigurationProperties(prefix = "app.deposit.proof-ocr")
public record ProofOcrProperties(String apiKey, String model, String baseUrl,
                                 Integer maxOutputTokens, BigDecimal reviewThreshold,
                                 Integer dateToleranceDays, Boolean required,
                                 java.util.List<String> requiredExemptReferenceTypes) {

    public ProofOcrProperties {
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
        if (dateToleranceDays == null || dateToleranceDays < 0) {
            dateToleranceDays = 3;
        }
        if (required == null) {
            required = Boolean.FALSE;
        }
        if (requiredExemptReferenceTypes == null || requiredExemptReferenceTypes.isEmpty()) {
            requiredExemptReferenceTypes = java.util.List.of("SETTLEMENT", "PAYOUT");
        }
    }
}
