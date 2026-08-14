package github.lms.lemuel.loan.adapter.out.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.ocr.VisionExtractionClient;
import github.lms.lemuel.loan.domain.ExtractedCollateralDocument;
import github.lms.lemuel.loan.domain.exception.CollateralDocumentOcrUnavailableException;
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
 * 담보서류 OCR 어댑터의 <b>필드 해석</b> 계약 — HTTP·봉투 해체는 shared-common 클라이언트 담당(ADR 0036).
 *
 * <p>핵심: 감정평가액은 지어내지 않는다(못 읽으면 503). 선순위·평가기준일 판독 실패는 null(대사가 판단).
 */
class GeminiCollateralDocumentOcrAdapterTest {

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
        ExtractedCollateralDocument doc = GeminiCollateralDocumentOcrAdapter.mapFields(fields("""
                {"ownerName":"홍길동","locationText":"서울시 강남구 역삼동 123-4",
                 "appraisedValue":"500,000,000원","seniorClaimAmount":"120,000,000",
                 "appraisalDate":"2026-08-10","confidence":"0.93"}
                """));

        assertThat(doc.ownerName()).isEqualTo("홍길동");
        assertThat(doc.locationText()).isEqualTo("서울시 강남구 역삼동 123-4");
        assertThat(doc.appraisedValue()).isEqualByComparingTo("500000000");
        assertThat(doc.seniorClaimAmount()).isEqualByComparingTo("120000000");
        assertThat(doc.appraisalDate()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(doc.confidence()).isEqualByComparingTo("0.93");
    }

    @Test
    @DisplayName("선순위·평가기준일 판독 실패는 null — 형식 파손 날짜도 null (대사가 판단)")
    void optionalFieldsFailSoft() {
        ExtractedCollateralDocument doc = GeminiCollateralDocumentOcrAdapter.mapFields(fields("""
                {"ownerName":null,"appraisedValue":"500000000",
                 "seniorClaimAmount":"읽을 수 없음","appraisalDate":"언제인지 모름","confidence":"0.85"}
                """));

        assertThat(doc.ownerName()).isNull();
        assertThat(doc.seniorClaimAmount()).isNull();
        assertThat(doc.appraisalDate()).isNull();
    }

    @Test
    @DisplayName("감정평가액을 못 읽으면 503 — 부분 결과를 만들지 않는다")
    void unreadableAppraisedValueIs503() {
        assertThatThrownBy(() -> GeminiCollateralDocumentOcrAdapter.mapFields(fields("""
                {"ownerName":"홍길동","appraisedValue":"알 수 없음","confidence":"0.9"}
                """)))
                .isInstanceOf(CollateralDocumentOcrUnavailableException.class)
                .hasMessageContaining("감정평가액");
        assertThatThrownBy(() -> GeminiCollateralDocumentOcrAdapter.mapFields(fields("""
                {"ownerName":"홍길동","confidence":"0.9"}
                """)))
                .isInstanceOf(CollateralDocumentOcrUnavailableException.class);
    }

    @Test
    @DisplayName("신뢰도 누락·범위 밖은 보수적 0.50 — 리뷰 큐로 흐르게")
    void confidenceFallsBackLow() {
        assertThat(GeminiCollateralDocumentOcrAdapter.mapFields(fields("""
                {"appraisedValue":"500000000"}
                """)).confidence()).isEqualByComparingTo("0.50");
        assertThat(GeminiCollateralDocumentOcrAdapter.mapFields(fields("""
                {"appraisedValue":"500000000","confidence":"7"}
                """)).confidence()).isEqualByComparingTo("0.50");
    }

    @Test
    @DisplayName("클라이언트 추출 실패는 503 동형 예외로 번역된다")
    void translatesClientFailureTo503() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"))
                .andRespond(withServerError());
        GeminiCollateralDocumentOcrAdapter adapter = new GeminiCollateralDocumentOcrAdapter(
                new VisionExtractionClient("https://generativelanguage.googleapis.com", "key",
                        "gemini-2.5-flash", 1024, builder));

        assertThatThrownBy(() -> adapter.extract(new byte[]{1}, "application/pdf"))
                .isInstanceOf(CollateralDocumentOcrUnavailableException.class);
    }

    @Test
    @DisplayName("API 키가 없으면 미구성 — 유스케이스가 503 으로 끊는다")
    void notConfiguredWithoutKey() {
        GeminiCollateralDocumentOcrAdapter adapter = new GeminiCollateralDocumentOcrAdapter(
                new VisionExtractionClient("https://generativelanguage.googleapis.com", " ",
                        "gemini-2.5-flash", 1024, RestClient.builder()));

        assertThat(adapter.isConfigured()).isFalse();
        assertThat(adapter.modelName()).isEqualTo("gemini-2.5-flash");
    }
}
