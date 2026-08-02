package github.lms.lemuel.card.adapter.out.external;

import github.lms.lemuel.card.application.port.out.FundingUnavailableException;
import github.lms.lemuel.card.application.port.out.LoadSellerFundingPort.SellerFunding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * account 내부 재원 API 와의 계약 검증. 재시도는 백오프 0 으로 지연 없이 확인한다.
 */
class AccountFundingAdapterTest {

    private static final String URI = "http://account-test/internal/account/sellers/777/funding";

    private MockRestServiceServer server;
    private RestClient.Builder builder;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl("http://account-test");
        server = MockRestServiceServer.bindTo(builder).build();
    }

    private AccountFundingAdapter adapter() {
        return new AccountFundingAdapter(builder.build(), Duration.ZERO);
    }

    @Test
    @DisplayName("문자열 금액을 BigDecimal 로 정확히 파싱한다")
    void parsesStringAmounts() {
        server.expect(requestTo(URI)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"sellerId\":\"777\",\"sellerPayable\":\"170000.00\",\"holdbackPayable\":\"10000.00\"}",
                        APPLICATION_JSON));

        SellerFunding funding = adapter().load("777");

        assertThat(funding.sellerPayable()).isEqualByComparingTo("170000.00");
        assertThat(funding.holdbackPayable()).isEqualByComparingTo("10000.00");
        server.verify();
    }

    @Test
    @DisplayName("X-Internal-Api-Key 헤더를 실어 보낸다 — 운영 account 는 fail-closed 다")
    void sendsInternalApiKeyHeader() {
        RestClient.Builder keyed = RestClient.builder().baseUrl("http://account-test");
        MockRestServiceServer keyedServer = MockRestServiceServer.bindTo(keyed).build();
        keyedServer.expect(requestTo(URI))
                .andExpect(header("X-Internal-Api-Key", "test-secret"))
                .andRespond(withSuccess(
                        "{\"sellerId\":\"777\",\"sellerPayable\":\"1\",\"holdbackPayable\":\"0\"}",
                        APPLICATION_JSON));

        new AccountFundingAdapter("http://account-test", "test-secret", keyed).load("777");

        keyedServer.verify();
    }

    @Test
    @DisplayName("5xx 는 재시도하고, 두 번째가 성공하면 값을 돌려준다")
    void retriesOnServerErrorThenSucceeds() {
        server.expect(requestTo(URI)).andRespond(withServerError());
        server.expect(requestTo(URI)).andRespond(withSuccess(
                "{\"sellerId\":\"777\",\"sellerPayable\":\"5\",\"holdbackPayable\":\"5\"}",
                APPLICATION_JSON));

        assertThat(adapter().load("777").sellerPayable()).isEqualByComparingTo("5");
        server.verify();
    }

    @Test
    @DisplayName("두 시도 모두 5xx 면 FundingUnavailableException — 폴백 없음")
    void exhaustedRetriesThrow() {
        server.expect(requestTo(URI)).andRespond(withServerError());
        server.expect(requestTo(URI)).andRespond(withServerError());

        assertThatThrownBy(() -> adapter().load("777"))
                .isInstanceOf(FundingUnavailableException.class);
        server.verify();
    }

    @Test
    @DisplayName("연결 실패(타임아웃 계열)도 재시도 후 실패로 번역된다")
    void connectionFailureTranslated() {
        server.expect(requestTo(URI)).andRespond(r -> { throw new IOException("simulated timeout"); });
        server.expect(requestTo(URI)).andRespond(r -> { throw new IOException("simulated timeout"); });

        assertThatThrownBy(() -> adapter().load("777"))
                .isInstanceOf(FundingUnavailableException.class);
        server.verify();
    }

    @Test
    @DisplayName("401 은 재시도하지 않는다 — 키가 틀린 건 재시도로 낫지 않는다")
    void unauthorizedIsNotRetried() {
        server.expect(requestTo(URI)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> adapter().load("777"))
                .isInstanceOf(FundingUnavailableException.class);
        server.verify();   // 단 1회만 등록 — 재시도했다면 verify 가 실패한다
    }

    @Test
    @DisplayName("응답 바디가 비면 실패로 본다 — 재원 0 으로 오해하지 않는다")
    void emptyBodyIsFailureNotZero() {
        server.expect(requestTo(URI)).andRespond(withSuccess("", APPLICATION_JSON));

        assertThatThrownBy(() -> adapter().load("777"))
                .isInstanceOf(FundingUnavailableException.class);
    }

    /**
     * 바디는 왔지만 금액 필드가 빠진 경우. {@link #emptyBodyIsFailureNotZero} 와 같은 이유로
     * 0 으로 정규화하면 장애가 "이 셀러는 자격 미달"로 둔갑한다 — 필드 단위로도 실패여야 한다.
     */
    @Test
    @DisplayName("금액 필드 누락도 실패로 본다 — 0 으로 정규화하지 않는다")
    void missingAmountFieldIsFailureNotZero() {
        server.expect(requestTo(URI)).andRespond(withSuccess(
                "{\"sellerId\":\"777\",\"holdbackPayable\":\"10000.00\"}", APPLICATION_JSON));

        assertThatThrownBy(() -> adapter().load("777"))
                .isInstanceOf(FundingUnavailableException.class)
                .hasMessageContaining("sellerPayable");
    }

    /** 숫자가 아닌 금액 문자열은 파싱 예외로 500 이 되는 대신 재원 조회 실패(503)로 번역돼야 한다. */
    @Test
    @DisplayName("숫자가 아닌 금액 문자열은 재원 조회 실패로 번역된다")
    void unparsableAmountTranslated() {
        server.expect(requestTo(URI)).andRespond(withSuccess(
                "{\"sellerId\":\"777\",\"sellerPayable\":\"N/A\",\"holdbackPayable\":\"0\"}",
                APPLICATION_JSON));

        assertThatThrownBy(() -> adapter().load("777"))
                .isInstanceOf(FundingUnavailableException.class);
    }
}
