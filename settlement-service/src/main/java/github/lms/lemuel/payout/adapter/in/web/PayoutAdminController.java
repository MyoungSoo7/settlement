package github.lms.lemuel.payout.adapter.in.web;

import github.lms.lemuel.payout.application.port.in.ExecutePayoutUseCase;
import github.lms.lemuel.payout.application.port.in.RetryFailedPayoutUseCase;
import github.lms.lemuel.payout.application.port.out.LoadPayoutPort;
import github.lms.lemuel.payout.domain.Payout;
import github.lms.lemuel.payout.domain.PayoutStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Payout Admin", description = "정산금 출금 운영자 콘솔")
@RestController
@RequestMapping("/admin/payouts")
public class PayoutAdminController {

    private final LoadPayoutPort loadPort;
    private final RetryFailedPayoutUseCase retryUseCase;
    private final ExecutePayoutUseCase executeUseCase;

    public PayoutAdminController(LoadPayoutPort loadPort,
                                  RetryFailedPayoutUseCase retryUseCase,
                                  ExecutePayoutUseCase executeUseCase) {
        this.loadPort = loadPort;
        this.retryUseCase = retryUseCase;
        this.executeUseCase = executeUseCase;
    }

    @Operation(summary = "FAILED 출금 목록")
    @GetMapping("/failed")
    public ResponseEntity<List<PayoutResponse>> listFailed(@RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(loadPort.findByStatus(PayoutStatus.FAILED, limit).stream()
                .map(PayoutResponse::from).toList());
    }

    @Operation(summary = "REQUESTED 출금 목록 (다음 배치 대기)")
    @GetMapping("/pending")
    public ResponseEntity<List<PayoutResponse>> listPending(@RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(loadPort.findByStatus(PayoutStatus.REQUESTED, limit).stream()
                .map(PayoutResponse::from).toList());
    }

    @Operation(summary = "출금 상세 조회")
    @GetMapping("/{id}")
    public ResponseEntity<PayoutResponse> get(@PathVariable Long id) {
        return loadPort.findById(id)
                .map(PayoutResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "FAILED 출금 재시도 — REQUESTED 로 복원하여 다음 배치에 재처리")
    @PostMapping("/{id}/retry")
    public ResponseEntity<PayoutResponse> retry(@PathVariable Long id) {
        Payout p = retryUseCase.retry(id, currentOperator());
        return ResponseEntity.ok(PayoutResponse.from(p));
    }

    @Operation(summary = "FAILED 출금 영구 취소 — 사유 필수 (감사 추적)")
    @PostMapping("/{id}/cancel")
    public ResponseEntity<PayoutResponse> cancel(@PathVariable Long id,
                                                  @RequestBody CancelRequest request) {
        Payout p = retryUseCase.cancel(id, currentOperator(), request.reason());
        return ResponseEntity.ok(PayoutResponse.from(p));
    }

    @Operation(summary = "수동 즉시 실행 — 정기 배치 외 즉시 송금이 필요할 때")
    @PostMapping("/execute-now")
    public ResponseEntity<Map<String, Object>> executeNow() {
        var report = executeUseCase.executeAllPending();
        return ResponseEntity.ok(Map.of(
                "succeeded", report.succeeded(),
                "failed", report.failed(),
                "limitedSkipped", report.limitedSkipped()
        ));
    }

    private static String currentOperator() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth.getName() == null ? "anonymous" : auth.getName();
    }

    public record CancelRequest(String reason) { }

    public record PayoutResponse(Map<String, Object> payout) {
        static PayoutResponse from(Payout p) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", p.getId());
            body.put("settlementId", p.getSettlementId());
            body.put("sellerId", p.getSellerId());
            body.put("amount", p.getAmount());
            body.put("status", p.getStatus().name());
            body.put("bank", p.getAccount().bankCode());
            body.put("account", p.getAccount().maskedAccountNumber());
            body.put("holder", p.getAccount().accountHolderName());
            body.put("firmBankingTxnId", p.getFirmBankingTransactionId());
            body.put("failureReason", p.getFailureReason());
            body.put("retryCount", p.getRetryCount());
            body.put("operatorId", p.getOperatorId());
            body.put("requestedAt", p.getRequestedAt());
            body.put("sentAt", p.getSentAt());
            body.put("completedAt", p.getCompletedAt());
            body.put("failedAt", p.getFailedAt());
            return new PayoutResponse(body);
        }
    }
}
