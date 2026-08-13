package github.lms.lemuel.card.adapter.out.llm;

import com.fasterxml.jackson.databind.JsonNode;
import github.lms.lemuel.card.application.port.out.ExtractReceiptFieldsPort;
import github.lms.lemuel.card.config.ReceiptOcrProperties;
import github.lms.lemuel.card.domain.ExtractedReceipt;
import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import github.lms.lemuel.common.ocr.VisionExtractionClient;
import github.lms.lemuel.common.ocr.VisionExtractionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Gemini 비전 기반 영수증 OCR — shared-common {@link VisionExtractionClient} 위에 영수증 도메인 지식만
 * 얹는다(ADR 0036). settlement 의 {@code GeminiTaxInvoiceOcrAdapter} 와 같은 구조.
 *
 * <p><b>무폴백</b>: 호출 실패·형식 파손·총액 판독 실패는 전부 {@code CARD_RECEIPT_OCR_UNAVAILABLE}(503).
 * 총액은 대사의 근거라 지어낼 수 없다. 상호명·거래일은 판독 실패를 null 로 표현하고(불일치 선고가 아니라
 * NEEDS_REVIEW 로 흐른다), 신뢰도 누락은 보수적 0.50 — 리뷰 큐행이다.
 */
@Component
public class GeminiReceiptOcrAdapter implements ExtractReceiptFieldsPort {

    private static final Logger log = LoggerFactory.getLogger(GeminiReceiptOcrAdapter.class);

    /** 모델이 신뢰도를 주지 않았을 때의 보수적 기본값 — 리뷰 큐로 흐르게 한다. */
    private static final BigDecimal FALLBACK_CONFIDENCE = new BigDecimal("0.50");

    private static final String PROMPT = """
            첨부한 한국 카드 결제 영수증 이미지에서 다음 필드를 읽어 JSON 으로만 답하라.
            추측하지 말고, 읽을 수 없는 필드는 null 로 둔다.
            {
              "merchantName": "상호명",
              "transactionDate": "거래일(YYYY-MM-DD)",
              "totalAmount": "결제 총액(숫자만)",
              "confidence": "판독 신뢰도 0~1"
            }
            """;

    private final VisionExtractionClient client;

    /** 생성자가 둘(운영용·테스트 주입용)이라 스프링이 쓸 쪽을 명시한다. */
    @org.springframework.beans.factory.annotation.Autowired
    public GeminiReceiptOcrAdapter(ReceiptOcrProperties properties) {
        this(new VisionExtractionClient(properties.baseUrl(), properties.apiKey(),
                properties.model(), properties.maxOutputTokens()));
    }

    /** 테스트 전용 — 클라이언트를 밖에서 주입한다. */
    GeminiReceiptOcrAdapter(VisionExtractionClient client) {
        this.client = client;
        if (!client.isConfigured()) {
            log.warn("app.card.receipt-ocr.api-key 미설정 — 영수증 첨부는 503 으로 응답합니다.");
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
    public ExtractedReceipt extract(byte[] content, String contentType) {
        JsonNode fields;
        try {
            fields = client.extractJson(content, contentType, PROMPT);
        } catch (VisionExtractionException e) {
            log.warn("영수증 OCR 추출 실패: {}", e.getMessage());
            throw new BusinessException(ErrorCode.CARD_RECEIPT_OCR_UNAVAILABLE,
                    "영수증 판독에 실패했습니다: " + e.getMessage());
        }
        return mapFields(fields);
    }

    /** 모델이 돌려준 JSON 객체를 영수증 추출 결과로 옮긴다 — 필드 해석의 정본. */
    static ExtractedReceipt mapFields(JsonNode fields) {
        return new ExtractedReceipt(
                text(fields, "merchantName"),
                transactionDate(text(fields, "transactionDate")),
                totalAmount(text(fields, "totalAmount")),
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

    /** 거래일 판독 실패는 null — 대사에서 NEEDS_REVIEW 로 흐른다 (총액과 달리 불일치 선고 근거가 아니다). */
    private static LocalDate transactionDate(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 콤마·원 표기 등 장식을 걷어내고 숫자만 남긴다. 총액이 없으면 지어내지 않고 끊는다. */
    private static BigDecimal totalAmount(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("[^0-9.]", "");
        if (digits.isEmpty() || digits.equals(".")) {
            throw new BusinessException(ErrorCode.CARD_RECEIPT_OCR_UNAVAILABLE,
                    "영수증 총액을 읽지 못했습니다: " + raw);
        }
        try {
            return new BigDecimal(digits);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.CARD_RECEIPT_OCR_UNAVAILABLE,
                    "영수증 총액을 읽지 못했습니다: " + raw);
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
