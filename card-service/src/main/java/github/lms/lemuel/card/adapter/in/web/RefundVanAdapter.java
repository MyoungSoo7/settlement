package github.lms.lemuel.card.adapter.in.web;

import github.lms.lemuel.card.application.port.in.RefundHoldUseCase;
import github.lms.lemuel.card.application.port.in.RefundHoldUseCase.RefundHoldCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * VAN 환불 어댑터 — VAN 네트워크가 매입 후 환불을 푸시하는 경로.
 *
 * <p>경로: {@code POST /van/v1/refunds}
 *
 * <p>환불(refund) 는 매입 후에만 가능하다. 매입 전 취소는 취소({@code /van/v1/voids})를 사용한다.
 */
@RestController
@RequestMapping("/van/v1/refunds")
public class RefundVanAdapter {

    private final RefundHoldUseCase refundHoldUseCase;

    public RefundVanAdapter(RefundHoldUseCase refundHoldUseCase) {
        this.refundHoldUseCase = refundHoldUseCase;
    }

    @PostMapping
    public ResponseEntity<VanRefundResponse> refund(
            @Valid @RequestBody VanRefundRequest request) {

        refundHoldUseCase.refund(new RefundHoldCommand(
                request.authorizationId(),
                request.reason()
        ));

        return ResponseEntity.ok(new VanRefundResponse(request.authorizationId(), "REFUNDED"));
    }

    // ── DTO ──

    /**
     * VAN 환불 요청.
     *
     * @param authorizationId 환불 대상 승인번호(자연키)
     * @param reason          환불 사유(optional)
     */
    public record VanRefundRequest(
            @NotBlank String authorizationId,
            String reason
    ) {
    }

    /**
     * VAN 환불 응답.
     *
     * @param authorizationId 환불된 승인번호
     * @param status          처리 결과 상태("REFUNDED")
     */
    public record VanRefundResponse(
            String authorizationId,
            String status
    ) {
    }
}
