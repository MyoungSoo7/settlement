package github.lms.lemuel.payout.adapter.in.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.payout.application.port.in.ExecutePayoutUseCase;
import github.lms.lemuel.payout.application.port.in.RecordPayoutBounceUseCase;
import github.lms.lemuel.payout.application.port.in.RetryFailedPayoutUseCase;
import github.lms.lemuel.payout.application.port.out.LoadPayoutPort;
import github.lms.lemuel.payout.domain.Payout;
import github.lms.lemuel.payout.domain.PayoutStatus;
import github.lms.lemuel.idempotency.adapter.out.persistence.ManualIdempotencyGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
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
    private final RecordPayoutBounceUseCase bounceUseCase;
    private final ManualIdempotencyGuard idempotency;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    public PayoutAdminController(LoadPayoutPort loadPort,
                                  RetryFailedPayoutUseCase retryUseCase,
                                  ExecutePayoutUseCase executeUseCase,
                                  RecordPayoutBounceUseCase bounceUseCase,
                                  ManualIdempotencyGuard idempotency,
                                  AuditLogger auditLogger,
                                  ObjectMapper objectMapper) {
        this.loadPort = loadPort;
        this.retryUseCase = retryUseCase;
        this.executeUseCase = executeUseCase;
        this.bounceUseCase = bounceUseCase;
        this.idempotency = idempotency;
        this.auditLogger = auditLogger;
        this.objectMapper = objectMapper;
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
    public ResponseEntity<PayoutResponse> retry(
            @PathVariable Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (isDuplicate(idempotencyKey, "payout:retry:" + id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        Payout p = retryUseCase.retry(id, currentOperator());
        auditLogger.record(AuditAction.PAYOUT_RETRIED, "Payout", String.valueOf(id),
                toJson(Map.of("operator", currentOperator(), "payoutId", id,
                        "settlementId", p.getSettlementId(), "amount", p.getAmount(),
                        "retryCount", p.getRetryCount())));
        return ResponseEntity.ok(PayoutResponse.from(p));
    }

    @Operation(summary = "FAILED 출금 영구 취소 — 사유 필수 (감사 추적)")
    @PostMapping("/{id}/cancel")
    public ResponseEntity<PayoutResponse> cancel(
            @PathVariable Long id,
            @RequestBody CancelRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (isDuplicate(idempotencyKey, "payout:cancel:" + id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        Payout p = retryUseCase.cancel(id, currentOperator(), request.reason());
        auditLogger.record(AuditAction.PAYOUT_CANCELED, "Payout", String.valueOf(id),
                toJson(Map.of("operator", currentOperator(), "payoutId", id,
                        "settlementId", p.getSettlementId(), "amount", p.getAmount(),
                        "reason", String.valueOf(request.reason()))));
        return ResponseEntity.ok(PayoutResponse.from(p));
    }

    @Operation(summary = "송금 반송 기록 — COMPLETED 송금이 은행단에서 반송되면 사유 기록 + 정정계좌 재지급")
    @PostMapping("/{id}/bounce")
    public ResponseEntity<Map<String, Object>> bounce(
            @PathVariable Long id,
            @RequestBody BounceRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (isDuplicate(idempotencyKey, "payout:bounce:" + id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        // 계좌 정정은 /admin/seller-bank-accounts 로 먼저 하고, 그 다음 이 반송 기록이 정정계좌로 재지급한다.
        var outcome = bounceUseCase.recordBounce(id, request.reason(), currentOperator());
        // 감사 추적은 서비스단(RecordPayoutBounceService)이 실자금 경로로 남긴다 — 여기선 응답만 조립.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bouncedPayoutId", id);
        body.put("reason", outcome.bounce().getReason());
        body.put("reissuedPayoutId", outcome.bounce().getResolvedPayoutId());
        if (outcome.reissuedPayout() != null) {
            body.put("reissued", PayoutResponse.from(outcome.reissuedPayout()).payout());
        }
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "수동 즉시 실행 — 정기 배치 외 즉시 송금이 필요할 때")
    @PostMapping("/execute-now")
    public ResponseEntity<Map<String, Object>> executeNow() {
        var report = executeUseCase.executeAllPending();
        // 실자금 이동 트리거 — 배치 외 수동 집행을 operator·결과와 함께 감사 기록.
        auditLogger.record(AuditAction.PAYOUT_EXECUTED, "PayoutBatch", "execute-now",
                toJson(Map.of("operator", currentOperator(),
                        "succeeded", report.succeeded(),
                        "failed", report.failed(),
                        "limitedSkipped", report.limitedSkipped())));
        return ResponseEntity.ok(Map.of(
                "succeeded", report.succeeded(),
                "failed", report.failed(),
                "limitedSkipped", report.limitedSkipped()
        ));
    }

    /** Idempotency-Key 가 있고 이미 선점됐으면 true(중복 → 409). 키가 없으면 항상 false(멱등 미적용). */
    private boolean isDuplicate(String idempotencyKey, String endpoint) {
        return idempotencyKey != null && !idempotencyKey.isBlank()
                && !idempotency.claim(idempotencyKey, endpoint, currentOperator());
    }

    private static String currentOperator() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth.getName() == null ? "anonymous" : auth.getName();
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"audit_serialization_failed\"}";
        }
    }

    public record CancelRequest(String reason) { }

    public record BounceRequest(String reason) { }

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
