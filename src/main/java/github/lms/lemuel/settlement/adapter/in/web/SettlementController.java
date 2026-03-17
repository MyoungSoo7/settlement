package github.lms.lemuel.settlement.adapter.in.web;

import github.lms.lemuel.settlement.adapter.in.web.response.SettlementResponse;
import github.lms.lemuel.settlement.application.port.in.GetSettlementUseCase;
import github.lms.lemuel.settlement.domain.Settlement;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Settlement API Controller
 */
@Validated
@RestController
@RequestMapping("/settlements")
@RequiredArgsConstructor
public class SettlementController {

    private final GetSettlementUseCase getSettlementUseCase;

    @GetMapping("/{id}")
    public ResponseEntity<SettlementResponse> getSettlement(
            @PathVariable @Positive(message = "정산 ID는 양수여야 합니다") Long id) {
        Settlement settlement = getSettlementUseCase.getSettlementById(id);
        return ResponseEntity.ok(SettlementResponse.from(settlement));
    }

    @GetMapping("/payment/{paymentId}")
    public ResponseEntity<SettlementResponse> getSettlementByPaymentId(
            @PathVariable @Positive(message = "결제 ID는 양수여야 합니다") Long paymentId) {
        var settlements = getSettlementUseCase.getSettlementsByPaymentId(paymentId);
        if (settlements.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(SettlementResponse.from(settlements.get(0)));
    }
}
