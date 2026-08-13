package github.lms.lemuel.tax.adapter.out.ocr;

import github.lms.lemuel.tax.application.exception.TaxOcrUnavailableException;
import github.lms.lemuel.tax.application.port.out.ExtractTaxInvoiceFieldsPort;
import github.lms.lemuel.tax.application.port.out.dto.OcrExtraction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 텍스트 레이어 기반 세금계산서 파서 — {@code app.tax.ocr.provider=text-layer}(기본).
 *
 * <p><b>왜 기본값인가</b>: PoC 전 구간(업로드 → 추출 → 대사 → 리뷰)을 API 키·네트워크·비용 없이 결정적으로
 * 돌릴 수 있어야 테스트와 데모가 재현 가능하다. 전자세금계산서 XML/텍스트 사본이나 텍스트 레이어가 살아 있는
 * PDF 변환 결과가 실제 입력이 된다.
 *
 * <p><b>AI 인 척 하지 않는다</b>: 결정적 파싱이므로 신뢰도는 항상 1.00 이고, 이미지처럼 텍스트가 없는 입력은
 * 값을 지어내는 대신 503 으로 끊어 {@code app.tax.ocr.provider=gemini} 설정을 요구한다. 스캔 이미지를 실제로
 * 읽는 것은 {@code GeminiTaxInvoiceOcrAdapter} 의 몫이다.
 */
@Component
@ConditionalOnProperty(name = "app.tax.ocr.provider", havingValue = "text-layer", matchIfMissing = true)
public class TextLayerTaxInvoiceOcrAdapter implements ExtractTaxInvoiceFieldsPort {

    private static final String MODEL = "text-layer-v1";

    /** 결정적 파서의 신뢰도 — 추정이 아니라 정의다. */
    private static final BigDecimal DETERMINISTIC_CONFIDENCE = new BigDecimal("1.00");

    private static final Pattern SUPPLIER =
            Pattern.compile("공급자[^0-9\\n]{0,20}(\\d{3}\\s*-?\\s*\\d{2}\\s*-?\\s*\\d{5})");
    private static final Pattern BUYER =
            Pattern.compile("공급받는자[^0-9\\n]{0,20}(\\d{3}\\s*-?\\s*\\d{2}\\s*-?\\s*\\d{5})");
    private static final Pattern WRITTEN_DATE =
            Pattern.compile("작성일자\\s*[:：]?\\s*(\\d{4})\\s*[-./]\\s*(\\d{1,2})\\s*[-./]\\s*(\\d{1,2})");
    private static final Pattern SUPPLY = Pattern.compile("공급\\s*가액\\s*[:：]?\\s*([0-9,]+)");
    private static final Pattern TAX = Pattern.compile("세\\s*액\\s*[:：]?\\s*([0-9,]+)");
    private static final Pattern TOTAL =
            Pattern.compile("(?:합계\\s*금액|총\\s*액|합\\s*계)\\s*[:：]?\\s*([0-9,]+)");
    private static final Pattern APPROVAL = Pattern.compile("승인번호\\s*[:：]?\\s*([A-Za-z0-9\\-]+)");

    @Override
    public boolean isConfigured() {
        return true;   // 외부 의존이 없다 — 항상 호출 가능
    }

    @Override
    public String modelName() {
        return MODEL;
    }

    @Override
    public OcrExtraction extract(byte[] content, String contentType) {
        if (content == null || content.length == 0) {
            throw new TaxOcrUnavailableException("스캔 내용이 비어 있습니다.");
        }
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (!type.startsWith("text/")) {
            throw new TaxOcrUnavailableException(
                    "텍스트 레이어 파서는 " + contentType + " 를 읽을 수 없습니다."
                            + " 이미지·PDF 스캔본은 app.tax.ocr.provider=gemini 로 AI OCR 을 사용하세요.");
        }
        String text = new String(content, StandardCharsets.UTF_8);
        return new OcrExtraction(
                group(SUPPLIER, text),
                group(BUYER, text),
                writtenDate(text),
                amount(SUPPLY, text, "공급가액"),
                amount(TAX, text, "세액"),
                amount(TOTAL, text, "합계금액"),
                group(APPROVAL, text),
                DETERMINISTIC_CONFIDENCE);
    }

    private static String group(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).replaceAll("\\s", "") : null;
    }

    private static LocalDate writtenDate(String text) {
        Matcher matcher = WRITTEN_DATE.matcher(text);
        if (!matcher.find()) {
            throw new TaxOcrUnavailableException("작성일자를 읽지 못했습니다.");
        }
        try {
            return LocalDate.of(Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
        } catch (DateTimeException e) {
            throw new TaxOcrUnavailableException("작성일자가 유효한 날짜가 아닙니다.", e);
        }
    }

    private static BigDecimal amount(Pattern pattern, String text, String label) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            throw new TaxOcrUnavailableException(label + "을(를) 읽지 못했습니다.");
        }
        String digits = matcher.group(1).replace(",", "");
        if (digits.isEmpty()) {
            throw new TaxOcrUnavailableException(label + "을(를) 읽지 못했습니다.");
        }
        return new BigDecimal(digits);
    }
}
