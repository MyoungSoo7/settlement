package github.lms.lemuel.loan.adapter.out.llm;

import com.fasterxml.jackson.databind.JsonNode;
import github.lms.lemuel.common.ocr.VisionExtractionClient;
import github.lms.lemuel.common.ocr.VisionExtractionException;
import github.lms.lemuel.loan.application.port.out.ExtractCollateralDocumentPort;
import github.lms.lemuel.loan.config.CollateralOcrProperties;
import github.lms.lemuel.loan.domain.ExtractedCollateralDocument;
import github.lms.lemuel.loan.domain.exception.CollateralDocumentOcrUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Gemini 비전 기반 담보서류 OCR — shared-common {@link VisionExtractionClient} 위에 담보서류 도메인
 * 지식만 얹는다(ADR 0036 확산). settlement 세금계산서·card 영수증·insurance 청약서 어댑터와 같은 구조.
 *
 * <p><b>무폴백</b>: 호출 실패·형식 파손·감정평가액 판독 실패는 전부
 * {@link CollateralDocumentOcrUnavailableException}(503). 평가액은 한도 산정의 원천이라 지어낼 수 없다.
 * 소유자·소재지·선순위·평가기준일은 판독 실패를 null 로 표현하고(선순위·평가기준일 null 은
 * NEEDS_REVIEW 로 흐른다), 신뢰도 누락은 보수적 0.50 — 리뷰 큐행이다.
 */
@Component
public class GeminiCollateralDocumentOcrAdapter implements ExtractCollateralDocumentPort {

    private static final Logger log = LoggerFactory.getLogger(GeminiCollateralDocumentOcrAdapter.class);

    /** 모델이 신뢰도를 주지 않았을 때의 보수적 기본값 — 리뷰 큐로 흐르게 한다. */
    private static final BigDecimal FALLBACK_CONFIDENCE = new BigDecimal("0.50");

    private static final String PROMPT = """
            첨부한 한국 담보 관련 서류(감정평가서 또는 등기사항전부증명서) 이미지에서 다음 필드를 읽어
            JSON 으로만 답하라. 추측하지 말고, 읽을 수 없는 필드는 null 로 둔다.
            주민등록번호 등 개인식별번호는 절대 포함하지 마라.
            {
              "ownerName": "소유자 성명(등기부 갑구)",
              "locationText": "소재지 표시",
              "appraisedValue": "감정평가액(숫자만)",
              "seniorClaimAmount": "선순위 근저당 채권최고액 합계(숫자만, 없으면 null)",
              "appraisalDate": "평가기준일(YYYY-MM-DD)",
              "confidence": "판독 신뢰도 0~1"
            }
            """;

    private final VisionExtractionClient client;

    /** 생성자가 둘(운영용·테스트 주입용)이라 스프링이 쓸 쪽을 명시한다. */
    @Autowired
    public GeminiCollateralDocumentOcrAdapter(CollateralOcrProperties properties) {
        this(new VisionExtractionClient(properties.baseUrl(), properties.apiKey(),
                properties.model(), properties.maxOutputTokens()));
    }

    /** 테스트 전용 — 클라이언트를 밖에서 주입한다. */
    GeminiCollateralDocumentOcrAdapter(VisionExtractionClient client) {
        this.client = client;
        if (!client.isConfigured()) {
            log.warn("app.loan.collateral-ocr.api-key 미설정 — 담보서류 첨부는 503 으로 응답합니다.");
        }
    }

    @Override
    public boolean isConfigured() {
        return client.isConfigured();
    }

    @Override
    public String modelName() {
        return client.modelName();
    }

    @Override
    public ExtractedCollateralDocument extract(byte[] content, String contentType) {
        JsonNode fields;
        try {
            fields = client.extractJson(content, contentType, PROMPT);
        } catch (VisionExtractionException e) {
            log.warn("담보서류 OCR 추출 실패: {}", e.getMessage());
            throw new CollateralDocumentOcrUnavailableException(
                    "담보서류 판독에 실패했습니다: " + e.getMessage());
        }
        return mapFields(fields);
    }

    /** 모델이 돌려준 JSON 객체를 담보서류 추출 결과로 옮긴다 — 필드 해석의 정본. */
    static ExtractedCollateralDocument mapFields(JsonNode fields) {
        return new ExtractedCollateralDocument(
                text(fields, "ownerName"),
                text(fields, "locationText"),
                requiredAmount(text(fields, "appraisedValue"), "감정평가액"),
                softAmount(text(fields, "seniorClaimAmount")),
                softDate(text(fields, "appraisalDate")),
                confidence(text(fields, "confidence")));
    }

    private static String text(JsonNode fields, String name) {
        JsonNode node = fields.path(name);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText("").trim();
        return value.isEmpty() ? null : value;
    }

    /** 평가기준일 판독 실패는 null — 대사에서 NEEDS_REVIEW 로 흐른다. */
    private static LocalDate softDate(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 콤마·원 표기 등 장식을 걷어내고 숫자만 남긴다. 필수 금액이 없으면 지어내지 않고 끊는다. */
    private static BigDecimal requiredAmount(String raw, String label) {
        BigDecimal parsed = parseAmount(raw);
        if (parsed == null) {
            throw new CollateralDocumentOcrUnavailableException(label + "을(를) 읽지 못했습니다: " + raw);
        }
        return parsed;
    }

    /** 선순위 채권최고액 판독 실패는 null — 대사가 신고값과 대조해 리뷰 여부를 정한다. */
    private static BigDecimal softAmount(String raw) {
        return parseAmount(raw);
    }

    private static BigDecimal parseAmount(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("[^0-9.]", "");
        if (digits.isEmpty() || digits.equals(".")) {
            return null;
        }
        try {
            return new BigDecimal(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal confidence(String raw) {
        if (raw == null) {
            return FALLBACK_CONFIDENCE;
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(raw.replaceAll("[^0-9.]", ""));
        } catch (RuntimeException e) {
            return FALLBACK_CONFIDENCE;
        }
        if (parsed.signum() < 0 || parsed.compareTo(BigDecimal.ONE) > 0) {
            return FALLBACK_CONFIDENCE;
        }
        return parsed;
    }
}
