package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.application.port.in.CapturePaymentPort;
import github.lms.lemuel.payment.application.port.in.CreatePaymentPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TossPaymentService 단위 테스트.
 *
 * <p>PG 호출은 {@link TossConfirmApiClient}(별도 빈)에 위임한다 — 같은 클래스 안에서 자기호출하면
 * 스프링 AOP 프록시를 타지 않아 {@code @Retry}/{@code @CircuitBreaker} 가 무력화되기 때문이다
 * (김영한 스프링 고급편 §14 "프록시와 내부 호출", 대안 3 구조 변경). 따라서 여기서는
 * <b>협력 빈에 위임하는지</b>를 검증하고, HTTP 응답 처리는 {@code TossConfirmApiClientTest} 가 맡는다.
 */
@ExtendWith(MockitoExtension.class)
class TossPaymentServiceTest {

    @Mock TossConfirmApiClient tossConfirmApiClient;
    @Mock CreatePaymentPort createPaymentPort;
    @Mock SavePaymentPort savePaymentPort;
    @Mock CapturePaymentPort capturePaymentPort;

    private TossPaymentService service;

    @BeforeEach
    void setup() {
        service = new TossPaymentService(tossConfirmApiClient, createPaymentPort,
                savePaymentPort, capturePaymentPort);
    }

    private PaymentDomain readyPayment() {
        return PaymentDomain.create(1L, new BigDecimal("10000"), "TOSS_PAYMENTS");
    }

    @Test
    @DisplayName("confirmTossPayment: Toss 승인 성공 시 READY→AUTHORIZED 저장 후 capture 결과 반환")
    void confirmTossPayment_success() {
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
    }

    @Test
    @DisplayName("confirmTossPayment: PG 확인은 프록시가 걸린 별도 빈에 위임하고, 결제 생성보다 먼저 수행한다")
    void confirmTossPayment_delegatesPgCallToProxiedBeanFirst() {
        PaymentDomain created = readyPayment();
        when(createPaymentPort.createPayment(any())).thenReturn(created);
        when(savePaymentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(capturePaymentPort.capturePayment(any())).thenReturn(created);

        service.confirmTossPayment(10L, "pay-key-1", "toss-order-1", 10000L);

        InOrder order = inOrder(tossConfirmApiClient, createPaymentPort);
        order.verify(tossConfirmApiClient).confirm("pay-key-1", "toss-order-1", 10000L);
        order.verify(createPaymentPort).createPayment(any());
    }

    @Test
    @DisplayName("confirmTossPayment: PG 확인이 실패하면 결제를 생성하지 않고 즉시 전파")
    void confirmTossPayment_pgFailureStopsFlow() {
        doThrow(new IllegalStateException("Toss PG 일시 장애"))
                .when(tossConfirmApiClient).confirm(any(), any(), any());

        assertThatThrownBy(() -> service.confirmTossPayment(10L, "pay-key-1", "toss-order-1", 10000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Toss PG 일시 장애");

        verify(createPaymentPort, never()).createPayment(any());
        verify(capturePaymentPort, never()).capturePayment(any());
    }

    @Test
    @DisplayName("confirmTossCartPayment: 여러 주문에 대해 순차적으로 결제 확인 처리")
    void confirmTossCartPayment_success() {
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
        // 장바구니 일괄 결제도 PG 확인은 1회만 — 프록시 빈에 위임
        verify(tossConfirmApiClient).confirm("pay-key-cart", "toss-order-cart", 10000L);
    }
}
