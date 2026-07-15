package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.application.port.in.CapturePaymentPort;
import github.lms.lemuel.payment.application.port.in.CreatePaymentPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * TossPaymentService 단위 테스트.
 *
 * <p>내부적으로 생성되는 RestTemplate 인스턴스를 리플렉션으로 꺼내
 * {@link MockRestServiceServer} 를 바인딩해 실제 HTTP 왕복 없이 Toss API 호출을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class TossPaymentServiceTest {

    private static final String TOSS_API_URL = "https://api.tosspayments.com/v1/payments/confirm";

    @Mock CreatePaymentPort createPaymentPort;
    @Mock SavePaymentPort savePaymentPort;
    @Mock CapturePaymentPort capturePaymentPort;

    private TossPaymentService service;
    private MockRestServiceServer server;

    @BeforeEach
    void setup() {
        service = new TossPaymentService(createPaymentPort, savePaymentPort, capturePaymentPort);
        ReflectionTestUtils.setField(service, "secretKey", "test_sk_dummy");
        ReflectionTestUtils.setField(service, "tossApiUrl", TOSS_API_URL);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        server = MockRestServiceServer.createServer(restTemplate);
    }

    private PaymentDomain readyPayment() {
        return PaymentDomain.create(1L, new BigDecimal("10000"), "TOSS_PAYMENTS");
    }

    @Test
    @DisplayName("confirmTossPayment: Toss 승인 성공 시 READY→AUTHORIZED 저장 후 capture 결과 반환")
    void confirmTossPayment_success() {
        server.expect(requestTo(TOSS_API_URL))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess("{\"status\":\"DONE\"}", MediaType.APPLICATION_JSON));

        PaymentDomain created = readyPayment();
        when(createPaymentPort.createPayment(any())).thenReturn(created);
        when(savePaymentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        PaymentDomain captured = PaymentDomain.rehydrate(1L, 10L, new BigDecimal("10000"), BigDecimal.ZERO,
                github.lms.lemuel.payment.domain.PaymentStatus.CAPTURED, "TOSS_PAYMENTS",
                "TOSS:tx-1", null, null, null);
        when(capturePaymentPort.capturePayment(any())).thenReturn(captured);

        PaymentDomain result = service.confirmTossPayment(10L, "pay-key-1", "toss-order-1", 10000L);

        assertThat(result.getStatus()).isEqualTo(github.lms.lemuel.payment.domain.PaymentStatus.CAPTURED);
        verify(savePaymentPort).save(any());
        verify(capturePaymentPort).capturePayment(created.getId());
        server.verify();
    }

    @Test
    @DisplayName("confirmTossCartPayment: 여러 주문에 대해 순차적으로 결제 확인 처리")
    void confirmTossCartPayment_success() {
        server.expect(requestTo(TOSS_API_URL))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess("{\"status\":\"DONE\"}", MediaType.APPLICATION_JSON));

        when(createPaymentPort.createPayment(any())).thenAnswer(inv ->
                PaymentDomain.create(1L, new BigDecimal("5000"), "TOSS_PAYMENTS"));
        when(savePaymentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        PaymentDomain captured1 = PaymentDomain.rehydrate(1L, 100L, new BigDecimal("5000"), BigDecimal.ZERO,
                github.lms.lemuel.payment.domain.PaymentStatus.CAPTURED, "TOSS_PAYMENTS",
                "TOSS:tx-1", null, null, null);
        PaymentDomain captured2 = PaymentDomain.rehydrate(2L, 200L, new BigDecimal("5000"), BigDecimal.ZERO,
                github.lms.lemuel.payment.domain.PaymentStatus.CAPTURED, "TOSS_PAYMENTS",
                "TOSS:tx-2", null, null, null);
        when(capturePaymentPort.capturePayment(any())).thenReturn(captured1, captured2);

        List<PaymentDomain> results = service.confirmTossCartPayment(
                List.of(100L, 200L), "pay-key-cart", "toss-order-cart", 10000L);

        assertThat(results).hasSize(2);
        verify(createPaymentPort, org.mockito.Mockito.times(2)).createPayment(any());
        server.verify();
    }

    @Test
    @DisplayName("callTossConfirmApi: 4xx 응답은 HttpClientErrorException → IllegalStateException 변환")
    void callTossConfirmApi_4xxThrowsIllegalState() {
        server.expect(requestTo(TOSS_API_URL))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"INVALID_REQUEST\",\"message\":\"bad request\"}"));

        assertThatThrownBy(() -> service.callTossConfirmApi("pay-key", "toss-order", 1000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Toss 결제 확인 실패");
        server.verify();
    }

    @Test
    @DisplayName("callTossConfirmApi: 2xx 가 아닌데 예외를 던지지 않는 응답(3xx)은 extractTossError 로 메시지 구성")
    void callTossConfirmApi_non2xxNoException() {
        server.expect(requestTo(TOSS_API_URL))
                .andRespond(withRawStatus(302)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"REDIRECT\",\"message\":\"unexpected\"}"));

        assertThatThrownBy(() -> service.callTossConfirmApi("pay-key", "toss-order", 1000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REDIRECT")
                .hasMessageContaining("unexpected");
        server.verify();
    }

    @Test
    @DisplayName("tossFallback: 원인이 IllegalStateException 이면 그대로 재전파")
    void tossFallback_rethrowsIllegalStateException() {
        IllegalStateException cause = new IllegalStateException("Toss 결제 확인 실패 (400 BAD_REQUEST): bad");

        assertThatThrownBy(() -> service.tossFallback("pay-key", "toss-order", 1000L, cause))
                .isSameAs(cause);
    }

    @Test
    @DisplayName("tossFallback: 서킷 OPEN/재시도 소진 등 그 외 원인은 일시 장애 메시지로 감싼다")
    void tossFallback_wrapsOtherThrowable() {
        RuntimeException cause = new RuntimeException("circuit open");

        assertThatThrownBy(() -> service.tossFallback("pay-key", "toss-order", 1000L, cause))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Toss PG 일시 장애")
                .hasCause(cause);
    }
}
