package github.lms.lemuel.deposit.adapter.out.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.ocr.VisionExtractionClient;
import github.lms.lemuel.deposit.domain.ExtractedTransferProof;
import github.lms.lemuel.deposit.domain.exception.DepositProofOcrUnavailableException;
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
 * 이체확인증 OCR 어댑터의 <b>필드 해석</b> 계약 — HTTP·봉투 해체는 shared-common 클라이언트 담당(ADR 0036).
 *
 * <p>핵심: 이체금액은 지어내지 않는다(못 읽으면 503). 입금자명·이체일 판독 실패는 null.
 */
class GeminiTransferProofOcrAdapterTest {

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
        ExtractedTransferProof proof = GeminiTransferProofOcrAdapter.mapFields(fields("""
                {"senderName":"홍길동","transferDate":"2026-08-12",
                 "transferAmount":"3,000,000원","confidence":"0.93"}
                """));

        assertThat(proof.senderName()).isEqualTo("홍길동");
        assertThat(proof.transferDate()).isEqualTo(LocalDate.of(2026, 8, 12));
        assertThat(proof.transferAmount()).isEqualByComparingTo("3000000");
        assertThat(proof.confidence()).isEqualByComparingTo("0.93");
    }

    @Test
    @DisplayName("입금자명·이체일 판독 실패는 null — 형식 파손 날짜도 null")
    void optionalFieldsFailSoft() {
        ExtractedTransferProof proof = GeminiTransferProofOcrAdapter.mapFields(fields("""
                {"senderName":null,"transferDate":"언제인지 모름","transferAmount":"3000000","confidence":"0.85"}
                """));

        assertThat(proof.senderName()).isNull();
        assertThat(proof.transferDate()).isNull();
    }

    @Test
    @DisplayName("이체금액을 못 읽으면 503 — 부분 결과를 만들지 않는다")
    void unreadableAmountIs503() {
        assertThatThrownBy(() -> GeminiTransferProofOcrAdapter.mapFields(fields("""
                {"senderName":"홍길동","transferDate":"2026-08-12","transferAmount":"알 수 없음","confidence":"0.9"}
                """)))
                .isInstanceOf(DepositProofOcrUnavailableException.class)
                .hasMessageContaining("이체금액");
        assertThatThrownBy(() -> GeminiTransferProofOcrAdapter.mapFields(fields("""
                {"senderName":"홍길동","confidence":"0.9"}
                """)))
                .isInstanceOf(DepositProofOcrUnavailableException.class);
    }

    @Test
    @DisplayName("신뢰도 누락·범위 밖은 보수적 0.50 — 리뷰 큐로 흐르게")
    void confidenceFallsBackLow() {
        assertThat(GeminiTransferProofOcrAdapter.mapFields(fields("""
                {"transferAmount":"3000000"}
                """)).confidence()).isEqualByComparingTo("0.50");
        assertThat(GeminiTransferProofOcrAdapter.mapFields(fields("""
                {"transferAmount":"3000000","confidence":"7"}
                """)).confidence()).isEqualByComparingTo("0.50");
    }

    @Test
    @DisplayName("클라이언트 추출 실패는 503 동형 예외로 번역된다")
    void translatesClientFailureTo503() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"))
                .andRespond(withServerError());
        GeminiTransferProofOcrAdapter adapter = new GeminiTransferProofOcrAdapter(
                new VisionExtractionClient("https://generativelanguage.googleapis.com", "key",
                        "gemini-2.5-flash", 1024, builder));

        assertThatThrownBy(() -> adapter.extract(new byte[]{1}, "image/png"))
                .isInstanceOf(DepositProofOcrUnavailableException.class);
    }

    @Test
    @DisplayName("API 키가 없으면 미구성 — 유스케이스가 503 으로 끊는다")
    void notConfiguredWithoutKey() {
        GeminiTransferProofOcrAdapter adapter = new GeminiTransferProofOcrAdapter(
                new VisionExtractionClient("https://generativelanguage.googleapis.com", " ",
                        "gemini-2.5-flash", 1024, RestClient.builder()));

        assertThat(adapter.isConfigured()).isFalse();
        assertThat(adapter.modelName()).isEqualTo("gemini-2.5-flash");
    }
}
