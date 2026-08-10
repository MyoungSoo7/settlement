package github.lms.lemuel.settlement.adapter.in.web.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.settlement.application.port.in.RerunSettlementBatchUseCase;
import github.lms.lemuel.settlement.domain.rerun.SettlementRerunReport;
import github.lms.lemuel.settlement.domain.rerun.SettlementRerunScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 정산 배치 재실행 운영자 콘솔.
 *
 * <p><b>배경</b>: 스케줄 실행(확정 03:00 · 홀드백 해제 03:00 · 지급 04:00)이 실패하거나 과거 일자를
 * 다시 돌려야 할 때, 지금까지의 복구 경로는 "DB 직접 조회 후 수동 조치"뿐이었다. 이 엔드포인트는
 * 그 경로를 감사 가능한 API 로 대체한다.
 *
 * <p><b>안전장치</b>:
 * <ul>
 *   <li>일자 게이트 — 미래 일자·허용 소급 범위 초과는 도메인이 거부(400). 어느 단계도 실행되지 않는다.</li>
 *   <li>자금 이동 분리 — {@code ALL} 은 재계산 경로만 전개한다. 송금({@code PAYOUT_EXECUTE})은
 *       운영자가 명시 지정해야 실행된다.</li>
 *   <li>멱등 — 확정은 REQUESTED 만, 홀드백 해제는 해제일 도래분만, 지급은 REQUESTED Payout 만
 *       대상이라 재실행이 중복 정산·중복 송금을 만들지 않는다.</li>
 *   <li>부분 실패 가시화 — 한 단계가 실패해도 나머지는 진행되고, 응답의 {@code complete=false} 와
 *       {@code failedSteps} 로 드러난다(실패를 200 뒤에 숨기지 않는다).</li>
 * </ul>
 *
 * <p><b>인가</b>: {@code /admin/settlements/**} → {@code hasRole("ADMIN")}. 정산 확정·송금을 트리거하므로
 * 조회 콘솔과 달리 MANAGER 에게 열지 않는다.
 *
 * <p><b>감사 추적</b>: 실행마다 {@link AuditAction#SETTLEMENT_BATCH_RERUN} 을 {@code audit_logs} 에
 * 남긴다 — 누가·어느 일자·어느 단계를·몇 건 처리했는지, 자금 이동 단계가 포함됐는지까지.
 */
@Tag(name = "Settlement Rerun Admin",
        description = "정산 배치 재실행 — 확정·홀드백 해제·지급 실행 (멱등, ADMIN)")
@RestController
@RequestMapping("/admin/settlements/rerun")
public class SettlementRerunAdminController {

    private final RerunSettlementBatchUseCase useCase;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    public SettlementRerunAdminController(RerunSettlementBatchUseCase useCase,
                                          AuditLogger auditLogger,
                                          ObjectMapper objectMapper) {
        this.useCase = useCase;
        this.auditLogger = auditLogger;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "정산 배치 재실행",
            description = "scope: CONFIRM(확정) · HOLDBACK_RELEASE(홀드백 해제) · PAYOUT_EXECUTE(지급 실행) · "
                    + "ALL(재계산 경로 일괄 — 송금 제외). targetDate 미지정 시 어제(KST). "
                    + "미래 일자·허용 소급 범위(기본 90일) 초과는 400. "
                    + "부분 실패는 200 + complete=false 로 반환되며 failedSteps 로 실패 단계를 식별한다.")
    @PostMapping
    public SettlementRerunResponse rerun(
            @RequestParam SettlementRerunScope scope,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate targetDate) {

        SettlementRerunReport report = useCase.rerun(scope, targetDate);

        // 감사 추적 — 자금 이동 단계 포함 여부를 명시적으로 남긴다(사후 조사에서 가장 먼저 묻는 항목).
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operator", currentOperator());
        detail.put("requestedScope", scope.name());
        detail.put("targetDate", String.valueOf(report.targetDate()));
        detail.put("movesMoney", scope.movesMoney());
        detail.put("complete", report.complete());
        detail.put("totalAffected", report.totalAffected());
        detail.put("failedSteps", report.failedSteps().stream().map(Enum::name).toList());
        auditLogger.record(AuditAction.SETTLEMENT_BATCH_RERUN, "SettlementRerun",
                "scope=" + scope + ",targetDate=" + report.targetDate(), toJson(detail));

        return SettlementRerunResponse.from(report);
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

    /**
     * 재실행 응답 — 도메인 리포트의 파생값({@code complete}·{@code totalAffected}·{@code failedSteps})을
     * 명시 필드로 펼쳐 웹 계약을 도메인 메서드 시그니처와 분리한다.
     */
    public record SettlementRerunResponse(LocalDate targetDate,
                                          boolean complete,
                                          long totalAffected,
                                          List<StepView> steps,
                                          List<SettlementRerunScope> failedSteps) {

        public record StepView(SettlementRerunScope scope, String status, long affected, String detail) { }

        static SettlementRerunResponse from(SettlementRerunReport report) {
            List<StepView> steps = report.steps().stream()
                    .map(s -> new StepView(s.scope(), s.status().name(), s.affected(), s.detail()))
                    .toList();
            return new SettlementRerunResponse(
                    report.targetDate(), report.complete(), report.totalAffected(), steps, report.failedSteps());
        }
    }
}
