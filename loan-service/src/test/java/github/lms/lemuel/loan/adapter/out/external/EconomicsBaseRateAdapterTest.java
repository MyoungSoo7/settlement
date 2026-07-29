package github.lms.lemuel.loan.adapter.out.external;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * economics-service 기준금리 클라이언트({@link EconomicsBaseRateAdapter}) 단위 테스트.
 *
 * <p>핵심은 <b>폴백 경로가 넓다</b>는 것 — ECOS 수집이 돌지 않은 환경에서는 {@code latest=null} 이
 * 정상 응답이고, 서비스 미기동이면 접속 자체가 실패한다. 어느 경우든 대출 신청이 막히면 안 되므로
 * 설정 기준금리로 폴백한다. 정상 조회 시에만 실측값이 금리 스냅샷의 출처가 된다.
 */
class EconomicsBaseRateAdapterTest {

    private static final String BASE = "http://localhost:8087";
    private static final BigDecimal FALLBACK = new BigDecimal("3.5");

    private record Fixture(EconomicsBaseRateAdapter adapter, MockRestServiceServer server) {
    }

    private Fixture newAdapter() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EconomicsBaseRateAdapter adapter = new EconomicsBaseRateAdapter(builder, BASE, FALLBACK);
        return new Fixture(adapter, server);
    }

    @Test
    @DisplayName("BASE_RATE 최신 관측치가 있으면 그 값을 돌려준다")
    void latestValue_isUsed() {
        Fixture f = newAdapter();
        f.server().expect(requestTo(BASE + "/api/economics/indicators/BASE_RATE/latest"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"code":"BASE_RATE","name":"한국은행 기준금리","unit":"%","cycle":"D",
                         "latest":{"observedDate":"2026-07-01","value":3.2500},
                         "change":{"amount":-0.2500,"ratePercent":-7.1429}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(f.adapter().currentBaseRatePercent()).isEqualByComparingTo("3.25");
        f.server().verify();
    }

    @Test
    @DisplayName("latest 가 null(미수집 환경) 이면 설정 기준금리로 폴백한다")
    void nullLatest_fallsBack() {
        Fixture f = newAdapter();
        f.server().expect(requestTo(BASE + "/api/economics/indicators/BASE_RATE/latest"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"code":"BASE_RATE","name":"한국은행 기준금리","unit":"%","cycle":"D",
                         "latest":null,"change":null}
                        """, MediaType.APPLICATION_JSON));

        assertThat(f.adapter().currentBaseRatePercent()).isEqualByComparingTo(FALLBACK);
    }

    @Test
    @DisplayName("지표 미등록(404) 이면 설정 기준금리로 폴백한다")
    void notFound_fallsBack() {
        Fixture f = newAdapter();
        f.server().expect(requestTo(BASE + "/api/economics/indicators/BASE_RATE/latest"))
                .andExpect(method(GET))
                .andRespond(withStatus(NOT_FOUND));

        assertThat(f.adapter().currentBaseRatePercent()).isEqualByComparingTo(FALLBACK);
    }

    @Test
    @DisplayName("API 5xx 실패도 설정 기준금리로 폴백한다 — economics 장애가 대출 신청을 막지 않는다")
    void serverError_fallsBack() {
        Fixture f = newAdapter();
        f.server().expect(requestTo(BASE + "/api/economics/indicators/BASE_RATE/latest"))
                .andExpect(method(GET))
                .andRespond(withStatus(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(f.adapter().currentBaseRatePercent()).isEqualByComparingTo(FALLBACK);
    }
}
