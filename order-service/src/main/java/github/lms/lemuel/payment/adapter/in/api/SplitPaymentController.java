package github.lms.lemuel.payment.adapter.in.api;

import github.lms.lemuel.payment.application.port.in.CreateSplitPaymentUseCase;
import github.lms.lemuel.payment.application.service.RefundSplitPaymentService;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentTender;
import github.lms.lemuel.payment.domain.TenderType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Split Payment", description = "분할결제 (포인트+카드+상품권)")
@RestController
@RequestMapping("/payments/split")
public class SplitPaymentController {

    private final CreateSplitPaymentUseCase createUseCase;
    private final RefundSplitPaymentService refundService;

    public SplitPaymentController(CreateSplitPaymentUseCase createUseCase,
                                   RefundSplitPaymentService refundService) {
        this.createUseCase = createUseCase;
        this.refundService = refundService;
    }

    @Operation(summary = "분할결제 생성",
            description = "여러 지불수단(포인트/상품권/카드)을 동시 사용. 합계가 주문 금액과 정확히 일치해야 한다.")
    @PostMapping
    public ResponseEntity<SplitPaymentResponse> create(@Valid @RequestBody SplitPaymentRequest request) {
        List<CreateSplitPaymentUseCase.TenderRequest> tenders = request.tenders().stream()
                .map(t -> new CreateSplitPaymentUseCase.TenderRequest(t.type(), t.amount()))
                .toList();
        PaymentDomain p = createUseCase.createSplit(request.orderId(), tenders);
        return ResponseEntity.status(HttpStatus.CREATED).body(SplitPaymentResponse.from(p));
    }

    @Operation(summary = "분할결제 환불 — 역순 처리",
            description = "sequence 역순으로 외부 PG → 내부 잔액 순서로 환불. 부분환불 지원.")
    @PostMapping("/{paymentId}/refund")
    public ResponseEntity<SplitPaymentResponse> refund(@PathVariable Long paymentId,
                                                       @RequestBody RefundRequest request) {
        PaymentDomain p = refundService.refundSplit(paymentId, request.amount());
        return ResponseEntity.ok(SplitPaymentResponse.from(p));
    }

    public record SplitPaymentRequest(
            @NotNull Long orderId,
            @NotEmpty List<TenderRequestDto> tenders) {}

    public record TenderRequestDto(@NotNull TenderType type, @Positive BigDecimal amount) {}

    public record RefundRequest(@Positive BigDecimal amount) {}

    public record SplitPaymentResponse(Map<String, Object> payment, List<Map<String, Object>> tenders) {
        static SplitPaymentResponse from(PaymentDomain p) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", p.getId());
            body.put("orderId", p.getOrderId());
            body.put("amount", p.getAmount());
            body.put("refundedAmount", p.getRefundedAmount());
            body.put("status", p.getStatus().name());
            body.put("paymentMethod", p.getPaymentMethod());
            body.put("isSplit", p.isSplit());

            List<Map<String, Object>> ts = p.getTenders().stream().map(SplitPaymentResponse::toTenderMap).toList();
            return new SplitPaymentResponse(body, ts);
        }

        private static Map<String, Object> toTenderMap(PaymentTender t) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("type", t.getType().name());
            m.put("amount", t.getAmount());
            m.put("refundedAmount", t.getRefundedAmount());
            m.put("refundableAmount", t.getRefundableAmount());
            m.put("pgTransactionId", t.getPgTransactionId());
            m.put("status", t.getStatus().name());
            m.put("sequence", t.getSequence());
            return m;
        }
    }
}
