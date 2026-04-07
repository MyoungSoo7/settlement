package github.lms.lemuel.returns.adapter.in.web;

import github.lms.lemuel.returns.adapter.in.web.dto.CreateReturnRequest;
import github.lms.lemuel.returns.adapter.in.web.dto.ReturnResponse;
import github.lms.lemuel.returns.application.port.in.ReturnUseCase;
import github.lms.lemuel.returns.domain.ReturnOrder;
import github.lms.lemuel.returns.domain.ReturnReason;
import github.lms.lemuel.returns.domain.ReturnStatus;
import github.lms.lemuel.returns.domain.ReturnType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 반품/교환 API Controller
 */
@Validated
@RestController
@RequestMapping("/api/returns")
@RequiredArgsConstructor
public class ReturnController {

    private final ReturnUseCase returnUseCase;

    @PostMapping
    public ResponseEntity<ReturnResponse> createReturn(@Valid @RequestBody CreateReturnRequest request) {
        ReturnOrder returnOrder = returnUseCase.createReturn(
                new ReturnUseCase.CreateReturnCommand(
                        request.getOrderId(),
                        request.getUserId(),
                        ReturnType.fromString(request.getType()),
                        ReturnReason.fromString(request.getReason()),
                        request.getReasonDetail(),
                        request.getRefundAmount()
                )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ReturnResponse.from(returnOrder));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReturnResponse> getReturn(
            @PathVariable @Positive(message = "반품/교환 ID는 양수여야 합니다") Long id) {
        ReturnOrder returnOrder = returnUseCase.getReturn(id);
        return ResponseEntity.ok(ReturnResponse.from(returnOrder));
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<List<ReturnResponse>> getReturnsByOrder(
            @PathVariable @Positive(message = "주문 ID는 양수여야 합니다") Long orderId) {
        List<ReturnResponse> returns = returnUseCase.getReturnsByOrderId(orderId)
                .stream()
                .map(ReturnResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(returns);
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<ReturnResponse>> getReturnsByUser(
            @PathVariable @Positive(message = "유저 ID는 양수여야 합니다") Long userId) {
        List<ReturnResponse> returns = returnUseCase.getReturnsByUserId(userId)
                .stream()
                .map(ReturnResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(returns);
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<ReturnResponse>> getReturnsByStatus(@PathVariable String status) {
        List<ReturnResponse> returns = returnUseCase.getReturnsByStatus(ReturnStatus.fromString(status))
                .stream()
                .map(ReturnResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(returns);
    }

    @PatchMapping("/{id}/approve")
    public ResponseEntity<ReturnResponse> approveReturn(
            @PathVariable @Positive(message = "반품/교환 ID는 양수여야 합니다") Long id) {
        ReturnOrder returnOrder = returnUseCase.approveReturn(id);
        return ResponseEntity.ok(ReturnResponse.from(returnOrder));
    }

    @PatchMapping("/{id}/reject")
    public ResponseEntity<ReturnResponse> rejectReturn(
            @PathVariable @Positive(message = "반품/교환 ID는 양수여야 합니다") Long id,
            @RequestBody Map<String, String> body) {
        String reason = body.getOrDefault("reason", "");
        ReturnOrder returnOrder = returnUseCase.rejectReturn(id, reason);
        return ResponseEntity.ok(ReturnResponse.from(returnOrder));
    }

    @PatchMapping("/{id}/ship")
    public ResponseEntity<ReturnResponse> shipReturn(
            @PathVariable @Positive(message = "반품/교환 ID는 양수여야 합니다") Long id,
            @RequestBody Map<String, String> body) {
        String trackingNumber = body.getOrDefault("trackingNumber", "");
        String carrier = body.getOrDefault("carrier", "");
        ReturnOrder returnOrder = returnUseCase.shipReturn(id, trackingNumber, carrier);
        return ResponseEntity.ok(ReturnResponse.from(returnOrder));
    }

    @PatchMapping("/{id}/receive")
    public ResponseEntity<ReturnResponse> receiveReturn(
            @PathVariable @Positive(message = "반품/교환 ID는 양수여야 합니다") Long id) {
        ReturnOrder returnOrder = returnUseCase.receiveReturn(id);
        return ResponseEntity.ok(ReturnResponse.from(returnOrder));
    }

    @PatchMapping("/{id}/complete")
    public ResponseEntity<ReturnResponse> completeReturn(
            @PathVariable @Positive(message = "반품/교환 ID는 양수여야 합니다") Long id) {
        ReturnOrder returnOrder = returnUseCase.completeReturn(id);
        return ResponseEntity.ok(ReturnResponse.from(returnOrder));
    }

    @PatchMapping("/{id}/cancel")
    public ResponseEntity<ReturnResponse> cancelReturn(
            @PathVariable @Positive(message = "반품/교환 ID는 양수여야 합니다") Long id) {
        ReturnOrder returnOrder = returnUseCase.cancelReturn(id);
        return ResponseEntity.ok(ReturnResponse.from(returnOrder));
    }
}
