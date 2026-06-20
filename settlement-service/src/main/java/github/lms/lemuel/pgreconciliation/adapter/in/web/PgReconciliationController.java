package github.lms.lemuel.pgreconciliation.adapter.in.web;

import github.lms.lemuel.pgreconciliation.application.port.in.ReconcilePgFileUseCase;
import github.lms.lemuel.pgreconciliation.application.port.in.ResolveDiscrepancyUseCase;
import github.lms.lemuel.pgreconciliation.application.port.out.LoadReconciliationRunPort;
import github.lms.lemuel.pgreconciliation.domain.ReconciliationDiscrepancy;
import github.lms.lemuel.pgreconciliation.domain.ReconciliationRun;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PG 정산파일 대사 운영자 콘솔.
 *
 * <p>업무 흐름:
 * <ol>
 *   <li>매일 PG 사로부터 받은 정산 CSV 파일을 운영자가 업로드 (POST /files)</li>
 *   <li>시스템이 즉시 비교 → ROUNDING_DIFF 자동 보정, 그 외는 PENDING 큐 등록</li>
 *   <li>운영자가 PENDING 차이 목록 확인 (GET /runs/{id})</li>
 *   <li>각 차이를 승인 (POST /discrepancies/{id}/approve) 또는 거절 (.../reject)</li>
 * </ol>
 */
@Tag(name = "PG Reconciliation", description = "PG 정산파일과 내부 결제 원장 대사")
@RestController
@RequestMapping("/admin/pg-reconciliation")
public class PgReconciliationController {

    private final ReconcilePgFileUseCase reconcileUseCase;
    private final ResolveDiscrepancyUseCase resolveUseCase;
    private final LoadReconciliationRunPort loadPort;

    public PgReconciliationController(ReconcilePgFileUseCase reconcileUseCase,
                                       ResolveDiscrepancyUseCase resolveUseCase,
                                       LoadReconciliationRunPort loadPort) {
        this.reconcileUseCase = reconcileUseCase;
        this.resolveUseCase = resolveUseCase;
        this.loadPort = loadPort;
    }

    @Operation(summary = "PG 정산파일 업로드 + 즉시 대사 실행",
            description = "CSV 파일을 multipart 로 업로드. 헤더: pg_transaction_id,amount,refunded_amount,fee,settled_date")
    @PostMapping(value = "/files", consumes = "multipart/form-data")
    public ResponseEntity<RunResponse> upload(
            @RequestParam("provider") String pgProvider,
            @RequestParam("targetDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate targetDate,
            @RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        ReconciliationRun run = reconcileUseCase.reconcile(
                pgProvider, targetDate, file.getOriginalFilename(),
                file.getInputStream(), currentOperatorId());
        return ResponseEntity.ok(RunResponse.from(run));
    }

    @Operation(summary = "최근 대사 실행 목록")
    @GetMapping("/runs")
    public ResponseEntity<List<RunResponse>> recentRuns(
            @RequestParam(defaultValue = "20") int limit) {
        List<RunResponse> runs = loadPort.findRecent(limit).stream()
                .map(RunResponse::from)
                .toList();
        return ResponseEntity.ok(runs);
    }

    @Operation(summary = "단일 대사 결과 + 자식 차이 목록")
    @GetMapping("/runs/{runId}")
    public ResponseEntity<RunDetailResponse> runDetail(@PathVariable Long runId) {
        return loadPort.findById(runId)
                .map(RunDetailResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "차이 승인 — 후속 SettlementAdjustment(역정산) 트리거")
    @PostMapping("/discrepancies/{id}/approve")
    public ResponseEntity<DiscrepancyResponse> approve(@PathVariable Long id,
                                                        @RequestBody ResolveRequest request) {
        ReconciliationDiscrepancy d = resolveUseCase.approve(id, currentOperatorId(), request.note());
        return ResponseEntity.ok(DiscrepancyResponse.from(d));
    }

    @Operation(summary = "차이 거절 — 무시 처리 (사유 필수)")
    @PostMapping("/discrepancies/{id}/reject")
    public ResponseEntity<DiscrepancyResponse> reject(@PathVariable Long id,
                                                       @RequestBody ResolveRequest request) {
        ReconciliationDiscrepancy d = resolveUseCase.reject(id, currentOperatorId(), request.note());
        return ResponseEntity.ok(DiscrepancyResponse.from(d));
    }

    private static String currentOperatorId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) return "anonymous";
        return auth.getName();
    }

    public record ResolveRequest(String note) {}

    public record RunResponse(Map<String, Object> run) {
        static RunResponse from(ReconciliationRun r) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", r.getId());
            body.put("pgProvider", r.getPgProvider());
            body.put("targetDate", r.getTargetDate());
            body.put("fileName", r.getFileName());
            body.put("status", r.getStatus().name());
            body.put("startedAt", r.getStartedAt());
            body.put("finishedAt", r.getFinishedAt());
            body.put("totalPgRows", r.getTotalPgRows());
            body.put("totalInternalRows", r.getTotalInternalRows());
            body.put("matchedCount", r.getMatchedCount());
            body.put("discrepancyCount", r.getDiscrepancyCount());
            body.put("autoCorrectedCount", r.getAutoCorrectedCount());
            body.put("operatorId", r.getOperatorId());
            return new RunResponse(body);
        }
    }

    public record RunDetailResponse(Map<String, Object> run, List<DiscrepancyResponse> discrepancies) {
        static RunDetailResponse from(ReconciliationRun r) {
            return new RunDetailResponse(
                    RunResponse.from(r).run(),
                    r.getDiscrepancies().stream().map(DiscrepancyResponse::from).toList()
            );
        }
    }

    public record DiscrepancyResponse(Map<String, Object> discrepancy) {
        static DiscrepancyResponse from(ReconciliationDiscrepancy d) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", d.getId());
            body.put("runId", d.getRunId());
            body.put("type", d.getType().name());
            body.put("paymentId", d.getPaymentId());
            body.put("pgTransactionId", d.getPgTransactionId());
            body.put("internalAmount", d.getInternalAmount());
            body.put("pgAmount", d.getPgAmount());
            body.put("difference", d.getDifference());
            body.put("status", d.getStatus().name());
            body.put("resolvedAt", d.getResolvedAt());
            body.put("resolvedBy", d.getResolvedBy());
            body.put("note", d.getNote());
            return new DiscrepancyResponse(body);
        }
    }
}
