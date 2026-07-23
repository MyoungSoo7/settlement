package github.lms.lemuel.payout.adapter.in.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.payout.application.port.in.BackfillMissingPayoutsUseCase;
import github.lms.lemuel.payout.domain.PayoutBackfillReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 미생성 Payout 멱등 백필 운영자 콘솔.
 *
 * <p><b>배경</b>: INV-6 탐지(settlementsWithoutPayout)를 근거로, 확정(DONE) 됐지만 Payout 이
 * 없는 과거 정산에 대해 지급유형별(IMMEDIATE·HOLDBACK_RELEASE) Payout 을 멱등·append-only 로
 * 신규 생성한다. DONE 정산·POSTED 원장은 절대 수정하지 않는다.
 *
 * <p><b>멱등성</b>: 재실행은 결과 불변. (settlement_id, payout_type) UNIQUE 제약이 이중 생성을 차단하고,
 * 이미 존재하는 Payout 은 스킵 카운트에 반영된다.
 *
 * <p><b>감사 추적</b>: POST 실행 시 {@link AuditAction#PAYOUT_BACKFILL_EXECUTED} 를 {@code audit_logs}
 * 에 기록한다 — 누가(operator)·언제(created_at 자동)·몇 건(created/skipped/failed/remaining)이 남는다.
 *
 * <p><b>운영 절차</b>:
 * <ol>
 *   <li>GET /status?from=&amp;to= 로 백필 대상 건수 확인</li>
 *   <li>POST /backfill?from=&amp;to= 로 백필 실행 (페이지 단위 커밋, 재실행 안전)</li>
 *   <li>응답 {@code complete}/{@code remaining} 으로 완료 판정, 잔존이 있으면 재실행</li>
 * </ol>
 *
 * <p><b>인가</b>: {@code /admin/payouts/**} 경로로 ADMIN 게이트를 상속한다.
 */
@Tag(name = "Payout Backfill Admin",
        description = "미생성 Payout 멱등 백필 — INV-6 탐지 결과 기반, append-only (ADMIN)")
@RestController
@RequestMapping("/admin/payouts/backfill")
public class PayoutBackfillAdminController {

    private final BackfillMissingPayoutsUseCase useCase;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    public PayoutBackfillAdminController(BackfillMissingPayoutsUseCase useCase,
                                         AuditLogger auditLogger,
                                         ObjectMapper objectMapper) {
        this.useCase = useCase;
        this.auditLogger = auditLogger;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "미생성 Payout 잔여 건수 조회 — 백필 실행 없이 현황 확인",
            description = "remaining = IMMEDIATE 미생성 + HOLDBACK_RELEASE 미생성(holdback 해제 완료 기준) 합계. "
                    + "complete=true 이면 백필 불필요.")
    @GetMapping("/status")
    public PayoutBackfillReport status(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return useCase.status(from, to);
    }

    @Operation(summary = "미생성 Payout 백필 실행 — 지급유형별 Payout 신규 생성 (멱등·append-only)",
            description = "페이지 단위 커밋으로 대량 안전. 재실행 idempotent(기존 Payout 은 스킵). "
                    + "응답에 created·skipped·failed·remaining·complete 포함. "
                    + "complete=false 이면 계좌 미해석 건이 남아 있음 — 계좌 등록 후 재실행. "
                    + "실행 이력(operator·건수)은 audit_logs 에 PAYOUT_BACKFILL_EXECUTED 로 기록됨.")
    @PostMapping
    public PayoutBackfillReport backfill(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer pageSize) {
        PayoutBackfillReport report = useCase.backfill(from, to, pageSize);

        // 감사 추적: 누가·언제·몇 건 — audit_logs 에 append-only 기록
        auditLogger.record(
                AuditAction.PAYOUT_BACKFILL_EXECUTED,
                "PayoutBackfill",
                "from=" + from + ",to=" + to,
                toJson(Map.of(
                        "operator",       currentOperator(),
                        "from",           String.valueOf(from),
                        "to",             String.valueOf(to),
                        "pageSize",       report.pageSize(),
                        "created",        report.created(),
                        "skipped",        report.skipped(),
                        "failed",         report.failed(),
                        "remaining",      report.remaining(),
                        "pagesCommitted", report.pagesCommitted(),
                        "complete",       report.complete()
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
