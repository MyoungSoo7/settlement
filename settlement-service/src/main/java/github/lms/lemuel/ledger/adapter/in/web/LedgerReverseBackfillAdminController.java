package github.lms.lemuel.ledger.adapter.in.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.ledger.application.port.in.BackfillMissingReverseUseCase;
import github.lms.lemuel.ledger.domain.LedgerReverseBackfillReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 원장 역분개 누락 백필 운영자 콘솔.
 *
 * <p>인가: {@code /admin/backfill/**} 경로는 shared-common SecurityConfig 의
 * {@code .requestMatchers("/admin/**").hasRole("ADMIN")} 게이트를 상속한다.
 *
 * <p><b>감사 추적</b>: POST /run 실행 시 {@link AuditAction#LEDGER_REVERSE_BACKFILL_EXECUTED} 를
 * {@code audit_logs} 에 기록한다 — 누가(operator)·언제(created_at 자동)·몇 건(enqueuedChargeback /
 * enqueuedReconciliation / totalEnqueued / remainingMissing)이 남는다.
 *
 * <p><b>운영 절차</b>:
 * <ol>
 *   <li>{@code GET /status} — 역분개 누락 건 수 확인 (실행 없음)</li>
 *   <li>{@code POST /run} — 백필 실행: 누락 조정을 {@code ledger_outbox} 에 적재
 *       (페이지 단위 커밋, 멱등, append-only)</li>
 *   <li>{@code LedgerOutboxPoller} 가 비동기로 역분개를 생성한 후</li>
 *   <li>{@code GET /status} 재확인 → {@code remainingMissing=0} 이면 완료</li>
 * </ol>
 *
 * <p><b>멱등 보장</b>: 반복 실행해도 {@code ledger_entries} 에 이중 분개가 생기지 않는다.
 * 역분개 실제 생성 시 {@code ReverseEntryService.existsByReference} 가 스킵하고,
 * {@code uq_ledger_reference_accounts} UNIQUE 제약이 DB 레벨 안전망을 제공한다.
 */
@Tag(name = "Ledger Reverse Backfill Admin",
        description = "원장 역분개 누락 멱등 백필 — 차지백·PG 대사 조정의 역분개 보정 (ADMIN)")
@RestController
@RequestMapping("/admin/backfill/ledger-reverse")
public class LedgerReverseBackfillAdminController {

    private final BackfillMissingReverseUseCase useCase;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    public LedgerReverseBackfillAdminController(BackfillMissingReverseUseCase useCase,
                                                 AuditLogger auditLogger,
                                                 ObjectMapper objectMapper) {
        this.useCase = useCase;
        this.auditLogger = auditLogger;
        this.objectMapper = objectMapper;
    }

    @Operation(
            summary = "역분개 누락 현황 조회 — 실행 없이 누락 건 수만 확인",
            description = "백필 실행 전/후 검증용. remainingMissing=0 이면 모든 차지백·PG 대사 조정에 역분개 존재")
    @GetMapping("/status")
    public LedgerReverseBackfillReport status() {
        return useCase.statusMissingReverse();
    }

    @Operation(
            summary = "역분개 백필 실행 — 차지백·PG 대사 조정의 역분개 누락분을 ledger_outbox 에 적재",
            description = "INV-5 missingReverseAdjustmentIds 기준 누락 조정을 페이지 단위로 조회해 "
                    + "REVERSE_CHARGEBACK / REVERSE_RECONCILIATION 아웃박스 작업을 적재한다. "
                    + "append-only·멱등(동일 조정 중복 적재해도 역분개는 1건). "
                    + "적재 후 LedgerOutboxPoller 가 비동기 처리하므로 "
                    + "remainingMissing > 0 이면 폴러 완료 후 GET /status 재확인 권장. "
                    + "실행 이력(operator·건수)은 audit_logs 에 LEDGER_REVERSE_BACKFILL_EXECUTED 로 기록됨.")
    @PostMapping("/run")
    public LedgerReverseBackfillReport run(
            @RequestParam(required = false) Integer pageSize) {
        LedgerReverseBackfillReport report = useCase.backfillMissingReverse(pageSize);

        // 감사 추적: 누가·언제·몇 건 — audit_logs 에 append-only 기록
        auditLogger.record(
                AuditAction.LEDGER_REVERSE_BACKFILL_EXECUTED,
                "LedgerReverseBackfill",
                "run",
                toJson(Map.of(
                        "operator",               currentOperator(),
                        "pageSize",               report.pageSize(),
                        "enqueuedChargeback",     report.enqueuedChargeback(),
                        "enqueuedReconciliation", report.enqueuedReconciliation(),
                        "totalEnqueued",          report.totalEnqueued(),
                        "remainingMissing",       report.remainingMissing(),
                        "pagesCommitted",         report.pagesCommitted(),
                        "complete",               report.complete()
                )));
        return report;
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
