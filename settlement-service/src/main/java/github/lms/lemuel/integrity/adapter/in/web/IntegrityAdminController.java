package github.lms.lemuel.integrity.adapter.in.web;

import github.lms.lemuel.integrity.application.port.in.IntegrityQueryUseCase;
import github.lms.lemuel.integrity.application.port.in.ProjectionReconciliationUseCase;
import github.lms.lemuel.integrity.domain.HoldbackStatusReport;
import github.lms.lemuel.integrity.domain.LedgerCompletenessReport;
import github.lms.lemuel.integrity.domain.PayoutReconReport;
import github.lms.lemuel.integrity.domain.ProcessedEventCount;
import github.lms.lemuel.integrity.domain.ProjectionDiffReport;
import github.lms.lemuel.integrity.domain.RefundAdjustmentReport;
import github.lms.lemuel.integrity.domain.StuckStateReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 정합성 검증 운영 콘솔 (Integrity Suite Phase A/B) — 전부 읽기 전용 GET.
 *
 * <p>설계: docs/design/settlement-integrity-suite.md. 인가는 SecurityConfig 의
 * {@code /admin/integrity/**} 매핑(ADMIN/MANAGER)으로 강제. settlement-copilot MCP
 * {@code integrity_check} 등이 이 API 를 순회 소비한다. 모든 응답에 기계 판정
 * {@code ok} + {@code reasons} 가 포함되어 에이전트 오진 여지를 줄인다.
 */
@Tag(name = "Integrity Admin", description = "정합성 검증 — 원장 완전성·지급 대사·홀드백·상태 체류 (read-only)")
@RestController
@RequestMapping("/admin/integrity")
public class IntegrityAdminController {

    private final IntegrityQueryUseCase useCase;
    private final ProjectionReconciliationUseCase projectionUseCase;

    public IntegrityAdminController(IntegrityQueryUseCase useCase,
                                    ProjectionReconciliationUseCase projectionUseCase) {
        this.useCase = useCase;
        this.projectionUseCase = projectionUseCase;
    }

    @Operation(summary = "INV-5 원장 완전성 — 확정 정산·환불 조정 ↔ 분개 존재/금액 대조",
            description = "시산표가 못 잡는 '통짜 누락'을 감지. grace(분) 이내의 미처리분은 pendingWithinGrace 로 구분(오탐 방지)")
    @GetMapping("/ledger-completeness")
    public LedgerCompletenessReport ledgerCompleteness(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Integer graceMinutes) {
        return useCase.checkLedgerCompleteness(date, graceMinutes);
    }

    @Operation(summary = "INV-6 지급 대사 — 그날 확정 정산 ↔ payout 금액·중복 대조",
            description = "과다 지급(payout > net)·이중 payout 은 위반, payout 미생성은 정보성")
    @GetMapping("/payout-recon")
    public PayoutReconReport payoutRecon(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return useCase.checkPayoutRecon(date);
    }

    @Operation(summary = "INV-7 홀드백 — 해제 기한 경과 미해제 감지",
            description = "HoldbackReleaseScheduler 침묵 정지를 감지. 해제일 당일은 배치 전일 수 있어 제외")
    @GetMapping("/holdback-status")
    public HoldbackStatusReport holdbackStatus() {
        return useCase.checkHoldbackStatus();
    }

    @Operation(summary = "INV-11 상태 체류 — PROCESSING/SENDING/RUNNING/PENDING 장기 체류 감지",
            description = "SENDING payout 은 이중지급 위험 1순위 — 재시도 전 펌뱅킹 거래 조회 필수")
    @GetMapping("/stuck")
    public StuckStateReport stuck(@RequestParam(required = false) Integer thresholdMinutes) {
        return useCase.checkStuckStates(thresholdMinutes);
    }

    @Operation(summary = "INV-8 지연 환불 조정 대사 — COMPLETED 환불(완료일 기준) ↔ 조정 존재 대조",
            description = "일일 대사(캡처일 축)가 못 보는 지연 환불의 조정 누락 감지. to 는 어제 이전 권장(당일 환불은 컨슈머 처리 중일 수 있음)")
    @GetMapping("/refund-adjustments")
    public RefundAdjustmentReport refundAdjustments(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return useCase.checkRefundAdjustments(from, to);
    }

    @Operation(summary = "INV-10 이벤트 회계 분자 — processed_events 그룹별 소비 건수",
            description = "발행측(order outbox PUBLISHED)과의 대조·판정은 copilot event_accounting 이 수행")
    @GetMapping("/processed-count")
    public List<ProcessedEventCount> processedCount(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return useCase.processedEventCounts(from, to);
    }

    @Operation(summary = "INV-12 프로젝션 행 diff — order 원천 결제 키 ↔ settlement_*_view 키 집합 대조",
            description = "체크섬 1차 스크리닝 후 불일치 시 누락/고아/금액불일치 id 를 상위 limit 건 특정. entity 는 현재 payment 만")
    @GetMapping("/projection-diff")
    public ProjectionDiffReport projectionDiff(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false, defaultValue = "payment") String entity,
            @RequestParam(required = false) Integer limit) {
        return projectionUseCase.reconcileProjection(date, entity, limit);
    }
}
