package github.lms.lemuel.payment.adapter.in.api;

import github.lms.lemuel.payment.adapter.in.dto.PaymentRequest;
import github.lms.lemuel.payment.adapter.in.dto.PaymentResponse;
import github.lms.lemuel.payment.adapter.in.dto.TossCartConfirmRequest;
import github.lms.lemuel.payment.adapter.in.dto.TossPaymentConfirmRequest;
import github.lms.lemuel.payment.application.TossPaymentService;
import github.lms.lemuel.payment.application.port.in.*;
import github.lms.lemuel.payment.domain.PaymentDomain;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * REST Controller for Payment API - Maps HTTP requests to use case ports
 */
@Tag(name = "Payment", description = "결제 생성/인증/캡처/환불 및 Toss 연동 API")
@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final CreatePaymentPort createPaymentPort;
    private final AuthorizePaymentPort authorizePaymentPort;
    private final CapturePaymentPort capturePaymentPort;
    private final RefundPaymentPort refundPaymentPort;
    private final GetPaymentPort getPaymentPort;
    private final TossPaymentService tossPaymentService;

    public PaymentController(CreatePaymentPort createPaymentPort,
                             AuthorizePaymentPort authorizePaymentPort,
                             CapturePaymentPort capturePaymentPort,
                             RefundPaymentPort refundPaymentPort,
                             GetPaymentPort getPaymentPort,
                             TossPaymentService tossPaymentService) {
        this.createPaymentPort = createPaymentPort;
        this.authorizePaymentPort = authorizePaymentPort;
        this.capturePaymentPort = capturePaymentPort;
        this.refundPaymentPort = refundPaymentPort;
        this.getPaymentPort = getPaymentPort;
        this.tossPaymentService = tossPaymentService;
    }

    @Operation(summary = "결제 생성", description = "주문 ID와 결제 수단을 기반으로 결제를 생성한다. 상태: READY")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청")
    })
    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(@Valid @RequestBody PaymentRequest request) {
        CreatePaymentCommand command = new CreatePaymentCommand(
            request.getOrderId(),
            request.getPaymentMethod()
        );

        PaymentDomain paymentDomain = createPaymentPort.createPayment(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(new PaymentResponse(paymentDomain));
    }

    @Operation(summary = "결제 인증", description = "결제 상태를 READY -> AUTHORIZED로 변경한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "인증 성공"),
            @ApiResponse(responseCode = "404", description = "결제를 찾을 수 없음")
    })
    @PatchMapping("/{id}/authorize")
    public ResponseEntity<PaymentResponse> authorizePayment(
            @Parameter(description = "결제 ID", required = true) @PathVariable Long id) {
        PaymentDomain paymentDomain = authorizePaymentPort.authorizePayment(id);
        return ResponseEntity.ok(new PaymentResponse(paymentDomain));
    }

    @Operation(summary = "결제 캡처", description = "결제 상태를 AUTHORIZED -> CAPTURED로 변경한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "캡처 성공"),
            @ApiResponse(responseCode = "404", description = "결제를 찾을 수 없음")
    })
    @PatchMapping("/{id}/capture")
    public ResponseEntity<PaymentResponse> capturePayment(
            @Parameter(description = "결제 ID", required = true) @PathVariable Long id) {
        PaymentDomain paymentDomain = capturePaymentPort.capturePayment(id);
        return ResponseEntity.ok(new PaymentResponse(paymentDomain));
    }

    @Operation(summary = "결제 환불", description = "전액 또는 부분 환불. amount 미지정 시 전액 환불. 부분 환불은 Idempotency-Key 헤더 필수.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "환불 성공"),
            @ApiResponse(responseCode = "400", description = "환불 금액/멱등 키 오류"),
            @ApiResponse(responseCode = "404", description = "결제를 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "환불 금액이 잔여 환불 가능 금액을 초과")
    })
    @PatchMapping("/{id}/refund")
    public ResponseEntity<PaymentResponse> refundPayment(
            @Parameter(description = "결제 ID", required = true) @PathVariable Long id,
            @Parameter(description = "부분 환불 금액 (생략 시 전액)", required = false)
                @RequestParam(value = "amount", required = false) java.math.BigDecimal amount,
            @Parameter(description = "부분 환불 멱등 키", required = false)
                @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        PaymentDomain paymentDomain = refundPaymentPort.refundPayment(id, amount, idempotencyKey);
    @Operation(summary = "결제 환불", description = "결제 상태를 CAPTURED -> REFUNDED로 변경한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "환불 성공"),
            @ApiResponse(responseCode = "404", description = "결제를 찾을 수 없음")
    })
    @PatchMapping("/{id}/refund")
    public ResponseEntity<PaymentResponse> refundPayment(
            @Parameter(description = "결제 ID", required = true) @PathVariable Long id) {
        PaymentDomain paymentDomain = refundPaymentPort.refundPayment(id);
        return ResponseEntity.ok(new PaymentResponse(paymentDomain));
    }

    @Operation(summary = "결제 단건 조회", description = "결제 ID로 결제 정보를 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "결제를 찾을 수 없음")
    })
    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponse> getPayment(
            @Parameter(description = "결제 ID", required = true) @PathVariable Long id) {
        PaymentDomain paymentDomain = getPaymentPort.getPayment(id);
        return ResponseEntity.ok(new PaymentResponse(paymentDomain));
    }

    /**
     * 토스페이먼츠 결제 확인
     * POST /payments/toss/confirm
     */
    @Operation(summary = "Toss 결제 확인", description = "토스페이먼츠 단건 결제 승인 요청을 처리한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "승인 성공"),
            @ApiResponse(responseCode = "400", description = "Toss 승인 실패"),
            @ApiResponse(responseCode = "404", description = "주문/결제를 찾을 수 없음")
    })
    @PostMapping("/toss/confirm")
    public ResponseEntity<PaymentResponse> confirmTossPayment(@Valid @RequestBody TossPaymentConfirmRequest request) {
        PaymentDomain paymentDomain = tossPaymentService.confirmTossPayment(
                request.getDbOrderId(),
                request.getPaymentKey(),
                request.getTossOrderId(),
                request.getAmount()
        );
        return ResponseEntity.ok(new PaymentResponse(paymentDomain));
    }

    /**
     * 토스페이먼츠 장바구니 일괄 결제 확인
     * POST /payments/toss/cart/confirm
     */
    @Operation(summary = "Toss 장바구니 일괄 결제 확인", description = "여러 주문에 대한 토스페이먼츠 일괄 결제 승인 요청을 처리한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "승인 성공"),
            @ApiResponse(responseCode = "400", description = "Toss 승인 실패")
    })
    @PostMapping("/toss/cart/confirm")
    public ResponseEntity<List<PaymentResponse>> confirmTossCartPayment(
            @Valid @RequestBody TossCartConfirmRequest request) {
        List<PaymentDomain> payments = tossPaymentService.confirmTossCartPayment(
                request.getOrderIds(),
                request.getPaymentKey(),
                request.getTossOrderId(),
                request.getTotalAmount()
        );
        List<PaymentResponse> responses = payments.stream()
                .map(PaymentResponse::new)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }
}
