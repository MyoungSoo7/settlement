package github.lms.lemuel.tax.adapter.out.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.ocr.VisionExtractionClient;
import github.lms.lemuel.common.ocr.VisionExtractionException;
import github.lms.lemuel.tax.application.exception.TaxOcrUnavailableException;
import github.lms.lemuel.tax.application.port.out.dto.OcrExtraction;
import github.lms.lemuel.tax.config.TaxOcrProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Gemini 비전 OCR 어댑터의 <b>세금계산서 필드 해석</b> 계약 — HTTP·봉투 해체는 shared-common
 * {@code VisionExtractionClient} 로 이관됐고(ADR 0036), 여기서는 도메인 지식(금액·작성일자·신뢰도 해석)과
 * 추출 실패의 503 번역을 못박는다.
 *
 * <p>핵심: 모델이 형식 파손·숫자 아닌 금액을 주면 <b>지어내지 않고</b> 503 이다.
 */
class GeminiTaxInvoiceOcrAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode fields(String innerJson) {
        try {
            return MAPPER.readTree(innerJson);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Gemini generateContent 응답 봉투 — parts[0].text 안에 우리가 요구한 JSON 이 들어온다. */
    private static String envelope(String innerJson) {
        try {
            return MAPPER.writeValueAsString(Map.of("candidates", List.of(
                    Map.of("content", Map.of("parts", List.of(Map.of("text", innerJson)))))));
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

        OcrExtraction extraction = GeminiTaxInvoiceOcrAdapter.mapFields(fields(inner));

        assertThat(extraction.supplierBusinessNo()).isEqualTo("101-81-00001");
        assertThat(extraction.writtenDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(extraction.supplyAmount()).isEqualByComparingTo("1000000");
        assertThat(extraction.taxAmount()).isEqualByComparingTo("100000");
        assertThat(extraction.totalAmount()).isEqualByComparingTo("1100000");
        assertThat(extraction.approvalNumber()).isEqualTo("TI-0000000005");
        assertThat(extraction.amountConfidence()).isEqualByComparingTo("0.93");
        assertThat(extraction.approvalNumberConfidence()).isEqualByComparingTo("0.93");
    }

    @Test
    @DisplayName("누락 필드는 null — 지어내지 않는다")
    void missingOptionalFieldsAreNull() {
        String inner = """
                {"supplierBusinessNo":"101-81-00001","writtenDate":"2026-08-01","supplyAmount":"1000",
                 "taxAmount":"100","totalAmount":"1100","confidence":"0.8"}
                """;

        OcrExtraction extraction = GeminiTaxInvoiceOcrAdapter.mapFields(fields(inner));

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

        OcrExtraction extraction = GeminiTaxInvoiceOcrAdapter.mapFields(fields(inner));

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

        assertThat(GeminiTaxInvoiceOcrAdapter.mapFields(fields(inner)).amountConfidence())
                .isEqualByComparingTo("0.50");
    }

    @Test
    @DisplayName("금액·작성일자를 못 읽으면 503 — 부분 결과를 만들지 않는다")
    void malformedFields() {
        String badAmount = """
                {"supplierBusinessNo":"101-81-00001","writtenDate":"2026-08-01",
                 "supplyAmount":"알 수 없음","taxAmount":"100","totalAmount":"1100","confidence":"0.9"}
                """;
        assertThatThrownBy(() -> GeminiTaxInvoiceOcrAdapter.mapFields(fields(badAmount)))
                .isInstanceOf(TaxOcrUnavailableException.class)
                .hasMessageContaining("금액");

        String badDate = """
                {"supplierBusinessNo":"101-81-00001","writtenDate":"언제인지 모름",
                 "supplyAmount":"1000","taxAmount":"100","totalAmount":"1100","confidence":"0.9"}
                """;
        assertThatThrownBy(() -> GeminiTaxInvoiceOcrAdapter.mapFields(fields(badDate)))
                .isInstanceOf(TaxOcrUnavailableException.class)
                .hasMessageContaining("작성일자");
    }

    @Test
    @DisplayName("클라이언트 추출 실패는 TaxOcrUnavailable(503) 로 번역된다")
    void translatesClientFailureTo503() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"))
                .andRespond(withServerError());
        GeminiTaxInvoiceOcrAdapter adapter = new GeminiTaxInvoiceOcrAdapter(new VisionExtractionClient(
                "https://generativelanguage.googleapis.com", "key", "gemini-2.5-flash", 1024, builder));

        assertThatThrownBy(() -> adapter.extract(new byte[]{1}, "image/png"))
                .isInstanceOf(TaxOcrUnavailableException.class)
                .hasCauseInstanceOf(VisionExtractionException.class);
    }

    @Test
    @DisplayName("정상 봉투 응답이면 HTTP 경유로도 추출 결과가 나온다")
    void extractsOverHttp() {
        String inner = """
                {"supplierBusinessNo":"101-81-00001","writtenDate":"2026-08-01","supplyAmount":"1000",
                 "taxAmount":"100","totalAmount":"1100","confidence":"0.9"}
                """;
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"))
                .andRespond(withSuccess(envelope(inner), MediaType.APPLICATION_JSON));
        GeminiTaxInvoiceOcrAdapter adapter = new GeminiTaxInvoiceOcrAdapter(new VisionExtractionClient(
                "https://generativelanguage.googleapis.com", "key", "gemini-2.5-flash", 1024, builder));

        assertThat(adapter.extract(new byte[]{1}, "image/png").totalAmount()).isEqualByComparingTo("1100");
    }

    @Test
    @DisplayName("API 키가 없으면 미구성 — 유스케이스가 503 으로 끊는다")
    void notConfiguredWithoutKey() {
        TaxOcrProperties props = new TaxOcrProperties("text-layer", "", null, null, null);

        assertThat(new GeminiTaxInvoiceOcrAdapter(props).isConfigured()).isFalse();
        assertThat(new GeminiTaxInvoiceOcrAdapter(props).modelName()).isNotBlank();
    }

    @Test
    @DisplayName("축별 신뢰도를 따로 읽는다 — 금액을 확신해도 승인번호 확신은 별개다")
    void mapsPerAxisConfidence() {
        String inner = """
                {"supplierBusinessNo":"101-81-00001","buyerBusinessNo":"101-81-00002",
                 "writtenDate":"2026-08-10","supplyAmount":"1000000","taxAmount":"100000",
                 "totalAmount":"1100000","approvalNumber":"TI-0000000005",
                 "amountConfidence":"0.97","approvalNumberConfidence":"0.33"}
                """;
        OcrExtraction extraction = GeminiTaxInvoiceOcrAdapter.mapFields(fields(inner));

        assertThat(extraction.amountConfidence()).isEqualByComparingTo("0.97");
        assertThat(extraction.approvalNumberConfidence()).isEqualByComparingTo("0.33");
    }

    @Test
    @DisplayName("구형 단일 confidence 응답도 받는다 — 추출을 통째로 잃는 것보다 낫다")
    void acceptsLegacySingleConfidence() {
        String inner = """
                {"supplierBusinessNo":"101-81-00001","buyerBusinessNo":"101-81-00002",
                 "writtenDate":"2026-08-10","supplyAmount":"1000000","taxAmount":"100000",
                 "totalAmount":"1100000","approvalNumber":"TI-0000000005","confidence":"0.88"}
                """;
        OcrExtraction extraction = GeminiTaxInvoiceOcrAdapter.mapFields(fields(inner));

        assertThat(extraction.amountConfidence()).isEqualByComparingTo("0.88");
        assertThat(extraction.approvalNumberConfidence()).isEqualByComparingTo("0.88");
    }

    @Test
    @DisplayName("프롬프트가 축별 신뢰도를 따로 판단하라고 지시한다")
    void promptDemandsIndependentConfidence() {
        // 이 지시가 빠지면 모델은 두 축에 같은 숫자를 주고 축별 게이트가 무력해진다.
        assertThat(GeminiTaxInvoiceOcrAdapter.promptForTest())
                .contains("amountConfidence")
                .contains("approvalNumberConfidence")
                .contains("축마다 따로 판단하라");
    }
}
