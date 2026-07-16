package github.lms.lemuel.ledger.adapter.in.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.ledger.application.port.in.RequeueFailedLedgerOutboxUseCase;
import github.lms.lemuel.ledger.domain.LedgerOutboxTask;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 원장 아웃박스 운영자 콘솔 — FAILED 항목 조회·일괄 재큐.
 *
 * <p>경로가 {@code /admin/outbox/**} 아래라 SecurityConfig 의 해당 매퍼(ROLE_ADMIN)를 그대로 상속한다
 * — 별도 보안 배선 없이 ADMIN 게이트가 걸린다. 재큐는 감사(LEDGER_OUTBOX_REQUEUED)로 추적한다.
 *
 * <p>재시도 한도(기본 10회)를 넘겨 FAILED 로 고정된 원장 작업은 자동 복구되지 않는다(무한 재시도 폭주
 * 방지). 원인 해소 후 운영자가 이 콘솔로 일괄 재큐하면 폴러가 정상 경로로 다시 처리한다(처리 멱등).
 */
@Tag(name = "Ledger Outbox Admin", description = "원장 아웃박스 FAILED 재큐 운영자 콘솔")
@RestController
@RequestMapping("/admin/outbox/ledger")
public class LedgerOutboxAdminController {

    private static final Logger log = LoggerFactory.getLogger(LedgerOutboxAdminController.class);

    /** 한 번에 재큐할 수 있는 최대 건수 — 대량 일괄 처리의 안전 상한. */
    private static final int MAX_REQUEUE = 500;

    private final RequeueFailedLedgerOutboxUseCase useCase;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    public LedgerOutboxAdminController(RequeueFailedLedgerOutboxUseCase useCase,
                                       AuditLogger auditLogger,
                                       ObjectMapper objectMapper) {
        this.useCase = useCase;
        this.auditLogger = auditLogger;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "FAILED 원장 아웃박스 목록 + 총 건수")
    @GetMapping("/failed")
    public ResponseEntity<Map<String, Object>> listFailed(@RequestParam(defaultValue = "50") int limit) {
        int capped = clamp(limit);
        List<Map<String, Object>> items = useCase.listFailed(capped).stream()
                .map(LedgerOutboxAdminController::toView).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("failedCount", useCase.countFailed());
        body.put("items", items);
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "FAILED 원장 아웃박스 일괄 재큐 — FAILED → PENDING, retry_count 리셋. 최대 500건.")
    @PostMapping("/requeue-failed")
    public ResponseEntity<Map<String, Object>> requeueFailed(@RequestParam(defaultValue = "100") int limit) {
        int capped = clamp(limit);
        int requeued = useCase.requeueFailed(capped);

        auditLogger.record(AuditAction.LEDGER_OUTBOX_REQUEUED, "LedgerOutbox", "requeue-failed",
                toJson(Map.of("operator", currentOperator(), "limit", capped, "requeued", requeued)));
        log.warn("[LedgerOutbox] operator={} 재큐 실행: limit={}, requeued={}",
                currentOperator(), capped, requeued);
        return ResponseEntity.ok(Map.of("requeued", requeued, "remainingFailed", useCase.countFailed()));
    }

    private static int clamp(int limit) {
        if (limit < 1) {
            return 1;
        }
        return Math.min(limit, MAX_REQUEUE);
    }

    private static Map<String, Object> toView(LedgerOutboxTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("type", t.type().name());
        m.put("settlementId", t.settlementId());
        m.put("refundId", t.refundId());
        m.put("retryCount", t.retryCount());
        return m;
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
}
