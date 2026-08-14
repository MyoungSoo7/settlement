package github.lms.lemuel.insurance.adapter.out.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.ocr.VisionExtractionClient;
import github.lms.lemuel.insurance.domain.ExtractedApplicationForm;
import github.lms.lemuel.insurance.domain.exception.ApplicationDocumentOcrUnavailableException;
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
 * 청약서 OCR 어댑터의 <b>필드 해석</b> 계약 — HTTP·봉투 해체는 shared-common 클라이언트 담당(ADR 0036).
 *
 * <p>핵심: 연 보험료는 지어내지 않는다(못 읽으면 503). 보장금액·청약일 판독 실패는 null(리뷰행).
 */
class GeminiApplicationFormOcrAdapterTest {

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
        ExtractedApplicationForm form = GeminiApplicationFormOcrAdapter.mapFields(fields("""
                {"contractorName":"홍길동","insuredName":"김피보","productName":"레무엘 종신보험",
                 "applicationDate":"2026-08-10","annualPremium":"1,200,000원",
                 "coverageAmount":"100,000,000","confidence":"0.93"}
                """));

        assertThat(form.contractorName()).isEqualTo("홍길동");
        assertThat(form.insuredName()).isEqualTo("김피보");
        assertThat(form.productName()).isEqualTo("레무엘 종신보험");
        assertThat(form.applicationDate()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(form.annualPremium()).isEqualByComparingTo("1200000");
        assertThat(form.coverageAmount()).isEqualByComparingTo("100000000");
        assertThat(form.confidence()).isEqualByComparingTo("0.93");
    }

    @Test
    @DisplayName("보장금액·청약일 판독 실패는 null — 형식 파손 날짜도 null (리뷰행)")
    void optionalFieldsFailSoft() {
        ExtractedApplicationForm form = GeminiApplicationFormOcrAdapter.mapFields(fields("""
                {"contractorName":null,"applicationDate":"언제인지 모름",
                 "annualPremium":"1200000","coverageAmount":"읽을 수 없음","confidence":"0.85"}
                """));

        assertThat(form.contractorName()).isNull();
        assertThat(form.applicationDate()).isNull();
        assertThat(form.coverageAmount()).isNull();
    }

    @Test
    @DisplayName("연 보험료를 못 읽으면 503 — 부분 결과를 만들지 않는다")
    void unreadablePremiumIs503() {
        assertThatThrownBy(() -> GeminiApplicationFormOcrAdapter.mapFields(fields("""
                {"contractorName":"홍길동","applicationDate":"2026-08-10",
                 "annualPremium":"알 수 없음","confidence":"0.9"}
                """)))
                .isInstanceOf(ApplicationDocumentOcrUnavailableException.class)
                .hasMessageContaining("보험료");
        assertThatThrownBy(() -> GeminiApplicationFormOcrAdapter.mapFields(fields("""
                {"contractorName":"홍길동","applicationDate":"2026-08-10","confidence":"0.9"}
                """)))
                .isInstanceOf(ApplicationDocumentOcrUnavailableException.class);
    }

    @Test
    @DisplayName("신뢰도 누락·범위 밖은 보수적 0.50 — 리뷰 큐로 흐르게")
    void confidenceFallsBackLow() {
        assertThat(GeminiApplicationFormOcrAdapter.mapFields(fields("""
                {"annualPremium":"1200000"}
                """)).confidence()).isEqualByComparingTo("0.50");
        assertThat(GeminiApplicationFormOcrAdapter.mapFields(fields("""
                {"annualPremium":"1200000","confidence":"7"}
                """)).confidence()).isEqualByComparingTo("0.50");
    }

    @Test
    @DisplayName("클라이언트 추출 실패는 503 동형 예외로 번역된다")
    void translatesClientFailureTo503() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"))
                .andRespond(withServerError());
        GeminiApplicationFormOcrAdapter adapter = new GeminiApplicationFormOcrAdapter(
                new VisionExtractionClient("https://generativelanguage.googleapis.com", "key",
                        "gemini-2.5-flash", 1024, builder));

        assertThatThrownBy(() -> adapter.extract(new byte[]{1}, "image/png"))
                .isInstanceOf(ApplicationDocumentOcrUnavailableException.class);
    }

    @Test
    @DisplayName("API 키가 없으면 미구성 — 유스케이스가 503 으로 끊는다")
    void notConfiguredWithoutKey() {
        GeminiApplicationFormOcrAdapter adapter = new GeminiApplicationFormOcrAdapter(
                new VisionExtractionClient("https://generativelanguage.googleapis.com", " ",
                        "gemini-2.5-flash", 1024, RestClient.builder()));

        assertThat(adapter.isConfigured()).isFalse();
        assertThat(adapter.modelName()).isEqualTo("gemini-2.5-flash");
    }
}
