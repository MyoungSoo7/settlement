package github.lms.lemuel.chargeback.adapter.in.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.chargeback.application.port.in.DecideChargebackUseCase;
import github.lms.lemuel.chargeback.application.port.in.OpenChargebackUseCase;
import github.lms.lemuel.chargeback.application.port.in.OpenChargebackUseCase.OpenChargebackCommand;
import github.lms.lemuel.chargeback.application.port.out.LoadChargebackPort;
import github.lms.lemuel.chargeback.domain.Chargeback;
import github.lms.lemuel.chargeback.domain.ChargebackReason;
import github.lms.lemuel.chargeback.domain.ChargebackSource;
import github.lms.lemuel.chargeback.domain.ChargebackStatus;
import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.idempotency.adapter.out.persistence.ManualIdempotencyGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 카드사 분쟁 운영자 콘솔.
 *
 * <p>{@code /admin/chargebacks/**} 는 SecurityConfig 에서 ROLE_ADMIN 강제.
 *
 * <p>API 표면:
 * <ul>
 *   <li>POST /admin/chargebacks                       — 수동 등록 (MANUAL)</li>
 *   <li>GET  /admin/chargebacks?status=OPEN&max=20    — 상태별 목록</li>
 *   <li>GET  /admin/chargebacks/{id}                  — 상세</li>
 *   <li>POST /admin/chargebacks/{id}/accept           — 셀러 책임 인정 → settlement_adjustments 차감</li>
 *   <li>POST /admin/chargebacks/{id}/reject           — 셀러 증빙 인정 → 분쟁 종결</li>
 * </ul>
 *
 * <p>PG webhook 자동 등록 채널은 별도 컨트롤러 (Phase 3) — HMAC 서명 검증 등 별도 보안 설계 필요.
 */
@Tag(name = "Chargeback Admin", description = "카드사 분쟁 운영자 콘솔")
@RestController
@RequestMapping("/admin/chargebacks")
public class ChargebackAdminController {

    private final OpenChargebackUseCase openUseCase;
    private final DecideChargebackUseCase decideUseCase;
    private final LoadChargebackPort loadPort;
    private final ManualIdempotencyGuard idempotency;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    public ChargebackAdminController(OpenChargebackUseCase openUseCase,
                                      DecideChargebackUseCase decideUseCase,
                                      LoadChargebackPort loadPort,
                                      ManualIdempotencyGuard idempotency,
                                      AuditLogger auditLogger,
                                      ObjectMapper objectMapper) {
        this.openUseCase = openUseCase;
        this.decideUseCase = decideUseCase;
        this.loadPort = loadPort;
        this.idempotency = idempotency;
        this.auditLogger = auditLogger;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "수동 분쟁 등록 (PG 통지 누락·시연용)")
    @PostMapping
    public ResponseEntity<ChargebackResponse> openManual(@RequestBody OpenManualRequest req) {
        Chargeback cb = openUseCase.open(new OpenChargebackCommand(
                req.paymentId(), req.settlementId(), req.amount(),
                req.reasonCode(), req.reasonDetail(),
                ChargebackSource.MANUAL, null
        ));
        return ResponseEntity.ok(ChargebackResponse.from(cb));
    }

    @Operation(summary = "상태별 분쟁 목록")
    @GetMapping
    public ResponseEntity<List<ChargebackResponse>> list(
            @RequestParam(defaultValue = "OPEN") ChargebackStatus status,
            @RequestParam(defaultValue = "20") int max) {
        List<ChargebackResponse> result = loadPort.findByStatus(status, max).stream()
                .map(ChargebackResponse::from).toList();
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "분쟁 상세")
    @GetMapping("/{id}")
    public ResponseEntity<ChargebackResponse> get(@PathVariable Long id) {
        return loadPort.findById(id)
                .map(ChargebackResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "셀러 책임 인정 — settlement_adjustments 음수 row 자동 생성")
    @PostMapping("/{id}/accept")
    public ResponseEntity<ChargebackResponse> accept(
            @PathVariable Long id,
            @RequestBody DecisionRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (isDuplicate(idempotencyKey, "chargeback:accept:" + id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        Chargeback cb = decideUseCase.accept(id, currentOperator(), req.note());
        // 셀러 환수(settlement_adjustments 차감) 트리거 — 결정 주체·금액을 감사 기록.
        auditLogger.record(AuditAction.CHARGEBACK_ACCEPTED, "Chargeback", String.valueOf(id),
                toJson(Map.of("operator", currentOperator(), "chargebackId", id,
                        "settlementId", String.valueOf(cb.getSettlementId()),
                        "amount", cb.getAmount(), "note", String.valueOf(req.note()))));
        return ResponseEntity.ok(ChargebackResponse.from(cb));
    }

    @Operation(summary = "셀러 증빙 인정 — 분쟁 종결, 정산 영향 없음. 사유 필수.")
    @PostMapping("/{id}/reject")
    public ResponseEntity<ChargebackResponse> reject(
            @PathVariable Long id,
            @RequestBody DecisionRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (isDuplicate(idempotencyKey, "chargeback:reject:" + id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        Chargeback cb = decideUseCase.reject(id, currentOperator(), req.note());
        auditLogger.record(AuditAction.CHARGEBACK_REJECTED, "Chargeback", String.valueOf(id),
                toJson(Map.of("operator", currentOperator(), "chargebackId", id,
                        "settlementId", String.valueOf(cb.getSettlementId()),
                        "amount", cb.getAmount(), "note", String.valueOf(req.note()))));
        return ResponseEntity.ok(ChargebackResponse.from(cb));
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

    public record OpenManualRequest(
            Long paymentId,
            Long settlementId,
            BigDecimal amount,
            ChargebackReason reasonCode,
            String reasonDetail
    ) { }

    public record DecisionRequest(String note) { }

    public record ChargebackResponse(Map<String, Object> chargeback) {
        static ChargebackResponse from(Chargeback c) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", c.getId());
            body.put("paymentId", c.getPaymentId());
            body.put("settlementId", c.getSettlementId());
            body.put("amount", c.getAmount());
            body.put("reasonCode", c.getReasonCode().name());
            body.put("reasonDetail", c.getReasonDetail());
            body.put("status", c.getStatus().name());
            body.put("source", c.getSource().name());
            body.put("pgChargebackId", c.getPgChargebackId());
            body.put("decidedBy", c.getDecidedBy());
            body.put("decisionNote", c.getDecisionNote());
            body.put("raisedAt", c.getRaisedAt());
            body.put("decidedAt", c.getDecidedAt());
            return new ChargebackResponse(body);
        }
    }
}
