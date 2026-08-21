package github.lms.lemuel.tax.adapter.out.llm;

import com.fasterxml.jackson.databind.JsonNode;
import github.lms.lemuel.common.ocr.VisionExtractionClient;
import github.lms.lemuel.common.ocr.VisionExtractionException;
import github.lms.lemuel.tax.application.exception.TaxOcrUnavailableException;
import github.lms.lemuel.tax.application.port.out.ExtractTaxInvoiceFieldsPort;
import github.lms.lemuel.tax.application.port.out.dto.OcrExtraction;
import github.lms.lemuel.tax.config.TaxOcrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Gemini(Generative Language API) 비전 기반 세금계산서 OCR — {@code app.tax.ocr.provider=gemini}.
 *
 * <p>HTTP 호출·응답 봉투 해체는 shared-common 의 {@link VisionExtractionClient}(ADR 0036)에 위임하고,
 * 여기는 <b>세금계산서 도메인 지식만</b> 남긴다 — 프롬프트(어떤 필드를 읽을지)와 필드 해석(금액·작성일자·
 * 신뢰도). card-service 영수증 OCR 이 같은 클라이언트를 쓴다.
 *
 * <p><b>폴백 없음</b>: 호출 실패·빈 응답·형식 파손·숫자 아닌 금액은 모두 {@link TaxOcrUnavailableException}
 * (503)이다. 모델이 확신 없이 채운 값을 세무 대사 근거로 저장하면 조용한 오대사가 되므로, 못 읽으면 못 읽었다고
 * 끊는 편이 안전하다. 신뢰도는 모델이 보고한 값을 그대로 싣고, 임계 미달은 상위에서 리뷰 대상이 된다.
 */
@Component
@ConditionalOnProperty(name = "app.tax.ocr.provider", havingValue = "gemini")
public class GeminiTaxInvoiceOcrAdapter implements ExtractTaxInvoiceFieldsPort {

    private static final Logger log = LoggerFactory.getLogger(GeminiTaxInvoiceOcrAdapter.class);

    /** 모델이 신뢰도를 주지 않았을 때의 보수적 기본값 — 리뷰 큐로 흐르게 한다. */
    private static final BigDecimal FALLBACK_CONFIDENCE = new BigDecimal("0.50");

    private static final String PROMPT = """
            첨부한 한국 전자세금계산서 이미지에서 다음 필드를 읽어 JSON 으로만 답하라.
            추측하지 말고, 읽을 수 없는 필드는 null 로 둔다.
            {
              "supplierBusinessNo": "공급자 등록번호(000-00-00000)",
              "buyerBusinessNo": "공급받는자 등록번호",
              "writtenDate": "작성일자(YYYY-MM-DD)",
              "supplyAmount": "공급가액(숫자만)",
              "taxAmount": "세액(숫자만)",
              "totalAmount": "합계금액(숫자만)",
              "approvalNumber": "승인번호",
              "amountConfidence": "금액 3종(공급가액·세액·합계) 판독 신뢰도 0~1",
              "approvalNumberConfidence": "승인번호 판독 신뢰도 0~1"
            }
            신뢰도는 축마다 따로 판단하라. 금액을 또렷하게 읽었다고 해서 승인번호까지 확신해서는
            안 된다. 도장·접힘·인쇄 번짐으로 특정 영역만 뭉개진 경우, 그 영역에서 읽은 값의
            신뢰도만 낮춰라.
            """;

    private final VisionExtractionClient client;

    /** 생성자가 둘(운영용·테스트 주입용)이라 스프링이 쓸 쪽을 명시한다 — provider=gemini 기동 필수. */
    @org.springframework.beans.factory.annotation.Autowired
    public GeminiTaxInvoiceOcrAdapter(TaxOcrProperties properties) {
        this(new VisionExtractionClient(properties.baseUrl(), properties.apiKey(),
                properties.model(), properties.maxOutputTokens()));
    }

    /** 테스트 전용 — 클라이언트를 밖에서 주입한다. */
    GeminiTaxInvoiceOcrAdapter(VisionExtractionClient client) {
        this.client = client;
        if (!client.isConfigured()) {
            log.warn("app.tax.ocr.api-key 미설정 — 세금계산서 OCR 업로드는 503 으로 응답합니다.");
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
    public OcrExtraction extract(byte[] content, String contentType) {
        JsonNode fields;
        try {
            fields = client.extractJson(content, contentType, PROMPT);
        } catch (VisionExtractionException e) {
            throw new TaxOcrUnavailableException("세금계산서 OCR 호출에 실패했습니다.", e);
        }
        return mapFields(fields);
    }

    /** 프롬프트 회귀 테스트 전용 — 축별 신뢰도 지시가 살아 있는지 확인한다. */
    static String promptForTest() {
        return PROMPT;
    }

    /** 모델이 돌려준 JSON 객체를 세금계산서 추출 결과로 옮긴다 — 필드 해석의 정본. */
    static OcrExtraction mapFields(JsonNode fields) {
        return new OcrExtraction(
                text(fields, "supplierBusinessNo"),
                text(fields, "buyerBusinessNo"),
                writtenDate(text(fields, "writtenDate")),
                amount(fields, "supplyAmount", "공급가액"),
                amount(fields, "taxAmount", "세액"),
                amount(fields, "totalAmount", "합계금액"),
                text(fields, "approvalNumber"),
                // 구형 응답(단일 "confidence")도 받는다 — 프롬프트를 바꿔도 모델이 옛 형식으로
                // 답하는 경우가 있고, 그때 추출을 통째로 잃는 것보다 같은 값을 양쪽에 쓰는 편이 낫다.
                confidence(firstPresent(text(fields, "amountConfidence"), text(fields, "confidence"))),
                confidence(firstPresent(text(fields, "approvalNumberConfidence"),
                        text(fields, "confidence"))));
    }

    /** 새 필드가 없으면 구형 필드로 물러난다. */
    private static String firstPresent(String preferred, String fallback) {
        return preferred != null ? preferred : fallback;
    }

    private static String text(JsonNode fields, String name) {
        JsonNode node = fields.path(name);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private static LocalDate writtenDate(String raw) {
        if (raw == null) {
            throw new TaxOcrUnavailableException("작성일자를 읽지 못했습니다.");
        }
        try {
            return LocalDate.parse(raw);
        } catch (RuntimeException e) {
            throw new TaxOcrUnavailableException("작성일자가 유효한 날짜가 아닙니다: " + raw, e);
        }
    }

    /** 콤마·원 표기 등 장식을 걷어내고 숫자만 남긴다. 숫자가 없으면 지어내지 않고 끊는다. */
    private static BigDecimal amount(JsonNode fields, String name, String label) {
        String raw = text(fields, name);
        String digits = raw == null ? "" : raw.replaceAll("[^0-9.]", "");
        if (digits.isEmpty() || digits.equals(".")) {
            throw new TaxOcrUnavailableException(label + " 금액을 읽지 못했습니다: " + raw);
        }
        try {
            return new BigDecimal(digits);
        } catch (NumberFormatException e) {
            throw new TaxOcrUnavailableException(label + " 금액을 읽지 못했습니다: " + raw, e);
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
