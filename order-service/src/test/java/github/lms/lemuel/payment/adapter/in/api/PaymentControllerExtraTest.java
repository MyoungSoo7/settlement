package github.lms.lemuel.payment.adapter.in.api;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.payment.application.TossPaymentService;
import github.lms.lemuel.payment.application.port.in.AuthorizePaymentPort;
import github.lms.lemuel.payment.application.port.in.CapturePaymentPort;
import github.lms.lemuel.payment.application.port.in.CreatePaymentPort;
import github.lms.lemuel.payment.application.port.in.GetPaymentPort;
import github.lms.lemuel.payment.application.port.in.RefundPaymentPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PaymentController 의 authorize/capture/Toss confirm/Toss cart confirm 엔드포인트 커버리지.
 * (기본 CRUD 경로는 {@link PaymentControllerTest} 가 담당.)
 */
@WebMvcTest(controllers = PaymentController.class)
@AutoConfigureMockMvc(addFilters = false)
class PaymentControllerExtraTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean CreatePaymentPort createPaymentPort;
    @MockitoBean AuthorizePaymentPort authorizePaymentPort;
    @MockitoBean CapturePaymentPort capturePaymentPort;
    @MockitoBean RefundPaymentPort refundPaymentPort;
    @MockitoBean GetPaymentPort getPaymentPort;
    @MockitoBean TossPaymentService tossPaymentService;

    private PaymentDomain domain(PaymentStatus status) {
        return PaymentDomain.rehydrate(1L, 10L, new BigDecimal("15000"), BigDecimal.ZERO,
                status, "CARD", "TOSS:tx-1", null, null, null);
    }

    @Test
    @DisplayName("PATCH /payments/{id}/authorize 는 AUTHORIZED 로 전이한다")
    void authorizePayment() throws Exception {
        when(authorizePaymentPort.authorizePayment(1L)).thenReturn(domain(PaymentStatus.AUTHORIZED));

        mockMvc.perform(patch("/payments/1/authorize"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));
    }

    @Test
    @DisplayName("PATCH /payments/{id}/capture 는 CAPTURED 로 전이한다")
    void capturePayment() throws Exception {
        when(capturePaymentPort.capturePayment(1L)).thenReturn(domain(PaymentStatus.CAPTURED));

        mockMvc.perform(patch("/payments/1/capture"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    @Test
    @DisplayName("POST /payments/toss/confirm 는 Toss 결제 확인을 위임한다")
    void confirmTossPayment() throws Exception {
        when(tossPaymentService.confirmTossPayment(10L, "pay-key", "toss-order-1", 15000L))
                .thenReturn(domain(PaymentStatus.CAPTURED));

        mockMvc.perform(post("/payments/toss/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dbOrderId":10,"paymentKey":"pay-key","tossOrderId":"toss-order-1","amount":15000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    @Test
    @DisplayName("POST /payments/toss/confirm 는 잘못된 요청을 거부한다")
    void confirmTossPaymentInvalidBody() throws Exception {
        mockMvc.perform(post("/payments/toss/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dbOrderId":10}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /payments/toss/cart/confirm 는 여러 결제 응답 목록을 반환한다")
    void confirmTossCartPayment() throws Exception {
        when(tossPaymentService.confirmTossCartPayment(any(), any(), any(), any()))
                .thenReturn(List.of(domain(PaymentStatus.CAPTURED), domain(PaymentStatus.CAPTURED)));

        mockMvc.perform(post("/payments/toss/cart/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderIds":[10,20],"paymentKey":"pay-key","tossOrderId":"toss-order-cart","totalAmount":30000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }
}
