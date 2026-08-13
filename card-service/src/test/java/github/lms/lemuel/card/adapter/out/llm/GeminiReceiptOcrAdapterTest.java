package github.lms.lemuel.card.adapter.out.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.card.domain.ExtractedReceipt;
import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import github.lms.lemuel.common.ocr.VisionExtractionClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

/**
 * 영수증 OCR 어댑터의 <b>필드 해석</b> 계약 — HTTP·봉투 해체는 shared-common 클라이언트 담당(ADR 0036).
 *
 * <p>핵심: 총액은 지어내지 않는다(못 읽으면 503). 거래일 판독 실패는 null(리뷰행) — 총액과 달리
 * 실패가 아니다.
 */
class GeminiReceiptOcrAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode fields(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("모델 JSON 을 추출 결과로 옮긴다 — 콤마·원 표기 정규화 포함")
    void mapsFields() {
        ExtractedReceipt extracted = GeminiReceiptOcrAdapter.mapFields(fields("""
                {"merchantName":"김밥천국 강남점","transactionDate":"2026-08-10",
                 "totalAmount":"12,000원","confidence":"0.93"}
                """));

        assertThat(extracted.merchantName()).isEqualTo("김밥천국 강남점");
        assertThat(extracted.transactionDate()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(extracted.totalAmount()).isEqualByComparingTo("12000");
        assertThat(extracted.confidence()).isEqualByComparingTo("0.93");
    }

    @Test
    @DisplayName("상호명·거래일 판독 실패는 null — 거래일 형식 파손도 null (리뷰행)")
    void optionalFieldsFailSoft() {
        ExtractedReceipt extracted = GeminiReceiptOcrAdapter.mapFields(fields("""
                {"merchantName":null,"transactionDate":"언제인지 모름","totalAmount":"9900","confidence":"0.85"}
                """));

        assertThat(extracted.merchantName()).isNull();
        assertThat(extracted.transactionDate()).isNull();
    }

    @Test
    @DisplayName("총액을 못 읽으면 503 — 부분 결과를 만들지 않는다")
    void unreadableTotalIs503() {
        assertThatThrownBy(() -> GeminiReceiptOcrAdapter.mapFields(fields("""
                {"merchantName":"가게","transactionDate":"2026-08-10","totalAmount":"알 수 없음","confidence":"0.9"}
                """)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CARD_RECEIPT_OCR_UNAVAILABLE));
        assertThatThrownBy(() -> GeminiReceiptOcrAdapter.mapFields(fields("""
                {"merchantName":"가게","transactionDate":"2026-08-10","confidence":"0.9"}
                """)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("신뢰도 누락·범위 밖은 보수적 0.50 — 리뷰 큐로 흐르게")
    void confidenceFallsBackLow() {
        assertThat(GeminiReceiptOcrAdapter.mapFields(fields("""
                {"merchantName":"가게","transactionDate":"2026-08-10","totalAmount":"1000"}
                """)).confidence()).isEqualByComparingTo("0.50");
        assertThat(GeminiReceiptOcrAdapter.mapFields(fields("""
                {"merchantName":"가게","transactionDate":"2026-08-10","totalAmount":"1000","confidence":"7"}
                """)).confidence()).isEqualByComparingTo("0.50");
    }

    @Test
    @DisplayName("클라이언트 추출 실패는 CARD_RECEIPT_OCR_UNAVAILABLE(503) 로 번역된다")
    void translatesClientFailureTo503() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"))
                .andRespond(withServerError());
        GeminiReceiptOcrAdapter adapter = new GeminiReceiptOcrAdapter(new VisionExtractionClient(
                "https://generativelanguage.googleapis.com", "key", "gemini-2.5-flash", 1024, builder));

        assertThatThrownBy(() -> adapter.extract(new byte[]{1}, "image/png"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CARD_RECEIPT_OCR_UNAVAILABLE));
    }
}
