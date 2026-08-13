package github.lms.lemuel.tax.adapter.out.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.tax.application.exception.TaxOcrUnavailableException;
import github.lms.lemuel.tax.application.port.out.dto.OcrExtraction;
import github.lms.lemuel.tax.config.TaxOcrProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Gemini 비전 OCR 어댑터의 <b>응답 해석</b> 계약 — HTTP 호출부는 통합 검증 대상이고, 여기서는
 * 모델이 뱉는 JSON 을 도메인 경계로 넘기기 전 어떻게 다루는지를 못박는다.
 *
 * <p>핵심: 모델이 빈 응답·형식 파손·숫자 아닌 금액을 주면 <b>지어내지 않고</b> 503 이다.
 */
class GeminiTaxInvoiceOcrAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Gemini generateContent 응답 봉투 — parts[0].text 안에 우리가 요구한 JSON 이 들어온다. */
    private static String envelope(String innerJson) {
        try {
            return MAPPER.writeValueAsString(Map.of("candidates", java.util.List.of(
                    Map.of("content", Map.of("parts", java.util.List.of(Map.of("text", innerJson)))))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("모델 JSON 을 추출 결과로 옮긴다")
    void parsesModelJson() {
        String inner = """
                {"supplierBusinessNo":"101-81-00001","buyerBusinessNo":"220-81-00001",
                 "writtenDate":"2026-08-01","supplyAmount":"1000000","taxAmount":"100000",
                 "totalAmount":"1100000","approvalNumber":"TI-0000000005","confidence":"0.93"}
                """;

        OcrExtraction extraction = GeminiTaxInvoiceOcrAdapter.parse(envelope(inner), MAPPER);

        assertThat(extraction.supplierBusinessNo()).isEqualTo("101-81-00001");
        assertThat(extraction.writtenDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(extraction.supplyAmount()).isEqualByComparingTo("1000000");
        assertThat(extraction.taxAmount()).isEqualByComparingTo("100000");
        assertThat(extraction.totalAmount()).isEqualByComparingTo("1100000");
        assertThat(extraction.approvalNumber()).isEqualTo("TI-0000000005");
        assertThat(extraction.confidence()).isEqualByComparingTo("0.93");
    }

    @Test
    @DisplayName("코드펜스로 감싸 오는 응답도 벗겨서 읽는다 (모델의 흔한 습관)")
    void stripsCodeFence() {
        String inner = """
                ```json
                {"supplierBusinessNo":"101-81-00001","writtenDate":"2026-08-01","supplyAmount":"1000",
                 "taxAmount":"100","totalAmount":"1100","confidence":"0.8"}
                ```
                """;

        OcrExtraction extraction = GeminiTaxInvoiceOcrAdapter.parse(envelope(inner), MAPPER);

        assertThat(extraction.supplyAmount()).isEqualByComparingTo("1000");
        assertThat(extraction.buyerBusinessNo()).isNull();
        assertThat(extraction.approvalNumber()).isNull();
    }

    @Test
    @DisplayName("숫자에 콤마·원 표기가 섞여 와도 금액으로 정규화한다")
    void normalizesAmounts() {
        String inner = """
                {"supplierBusinessNo":"101-81-00001","writtenDate":"2026-08-01",
                 "supplyAmount":"1,000,000원","taxAmount":"100,000","totalAmount":"1,100,000","confidence":"0.9"}
                """;

        OcrExtraction extraction = GeminiTaxInvoiceOcrAdapter.parse(envelope(inner), MAPPER);

        assertThat(extraction.supplyAmount()).isEqualByComparingTo("1000000");
        assertThat(extraction.totalAmount()).isEqualByComparingTo("1100000");
    }

    @Test
    @DisplayName("신뢰도 누락 시 보수적 기본값을 쓴다 — 리뷰 큐로 흐르게")
    void missingConfidenceFallsBackLow() {
        String inner = """
                {"supplierBusinessNo":"101-81-00001","writtenDate":"2026-08-01","supplyAmount":"1000",
                 "taxAmount":"100","totalAmount":"1100"}
                """;

        assertThat(GeminiTaxInvoiceOcrAdapter.parse(envelope(inner), MAPPER).confidence())
                .isEqualByComparingTo("0.50");
    }

    @Test
    @DisplayName("빈 응답·후보 없음은 503")
    void emptyResponse() {
        assertThatThrownBy(() -> GeminiTaxInvoiceOcrAdapter.parse("{}", MAPPER))
                .isInstanceOf(TaxOcrUnavailableException.class);
        assertThatThrownBy(() -> GeminiTaxInvoiceOcrAdapter.parse(null, MAPPER))
                .isInstanceOf(TaxOcrUnavailableException.class);
        assertThatThrownBy(() -> GeminiTaxInvoiceOcrAdapter.parse(envelope("   "), MAPPER))
                .isInstanceOf(TaxOcrUnavailableException.class);
    }

    @Test
    @DisplayName("JSON 이 아니거나 금액을 못 읽으면 503 — 부분 결과를 만들지 않는다")
    void malformedResponse() {
        assertThatThrownBy(() -> GeminiTaxInvoiceOcrAdapter.parse(envelope("계산서를 읽을 수 없습니다"), MAPPER))
                .isInstanceOf(TaxOcrUnavailableException.class);

        String badAmount = """
                {"supplierBusinessNo":"101-81-00001","writtenDate":"2026-08-01",
                 "supplyAmount":"알 수 없음","taxAmount":"100","totalAmount":"1100","confidence":"0.9"}
                """;
        assertThatThrownBy(() -> GeminiTaxInvoiceOcrAdapter.parse(envelope(badAmount), MAPPER))
                .isInstanceOf(TaxOcrUnavailableException.class)
                .hasMessageContaining("금액");

        String badDate = """
                {"supplierBusinessNo":"101-81-00001","writtenDate":"언제인지 모름",
                 "supplyAmount":"1000","taxAmount":"100","totalAmount":"1100","confidence":"0.9"}
                """;
        assertThatThrownBy(() -> GeminiTaxInvoiceOcrAdapter.parse(envelope(badDate), MAPPER))
                .isInstanceOf(TaxOcrUnavailableException.class)
                .hasMessageContaining("작성일자");
    }

    @Test
    @DisplayName("요청 본문에 이미지가 inline_data 로 실린다 (base64 + mime)")
    void buildsInlineImageRequest() {
        Map<String, Object> body = GeminiTaxInvoiceOcrAdapter.buildBody(new byte[]{1, 2, 3}, "image/png");

        String json = body.toString();
        assertThat(json).contains("inline_data").contains("image/png").contains("AQID");   // base64(1,2,3)
        assertThat(json).contains("application/json");   // responseMimeType — 구조화 응답 강제
    }

    @Test
    @DisplayName("API 키가 없으면 미구성 — 유스케이스가 503 으로 끊는다")
    void notConfiguredWithoutKey() {
        TaxOcrProperties props = new TaxOcrProperties("text-layer", "", null, null, null);

        assertThat(new GeminiTaxInvoiceOcrAdapter(props).isConfigured()).isFalse();
        assertThat(new GeminiTaxInvoiceOcrAdapter(props).modelName()).isNotBlank();
    }
}
