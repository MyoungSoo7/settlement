package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.ApproveExpenseReportUseCase;
import github.lms.lemuel.card.application.port.in.CreateExpenseReportFromCaptureUseCase;
import github.lms.lemuel.card.application.port.in.QueryBudgetUtilizationUseCase;
import github.lms.lemuel.card.application.port.in.RejectExpenseReportUseCase;
import github.lms.lemuel.card.application.port.in.SubmitExpenseReportUseCase;
import github.lms.lemuel.card.application.port.out.LoadDepartmentBudgetPort;
import github.lms.lemuel.card.application.port.out.LoadExpenseReceiptPort;
import github.lms.lemuel.card.application.port.out.LoadExpenseReportPort;
import github.lms.lemuel.card.application.port.out.SaveExpenseReportPort;
import github.lms.lemuel.card.application.port.out.UpdateDepartmentBudgetPort;
import github.lms.lemuel.card.config.ReceiptOcrProperties;
import github.lms.lemuel.card.domain.ExpenseReceiptStatus;
import github.lms.lemuel.card.domain.ExpenseReport;
import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 사후 지출관리 워크플로 유스케이스 구현.
 *
 * <h3>승인 경로 비결합</h3>
 * <ul>
 *   <li>이 서비스는 {@code AuthorizeCardService} 또는 {@code AuthorizeCardUseCase} 를 참조하지 않는다.</li>
 *   <li>{@code DeclineReason} 에 {@code PENDING_APPROVAL} 을 추가하지 않는다.</li>
 *   <li>비결합 여부는 {@code ExpenseWorkflowDecouplingTest}(ArchUnit) 가 빌드 타임에 강제한다.</li>
 * </ul>
 *
 * <h3>DRAFT 자동 생성</h3>
 * <ol>
 *   <li>captureId 멱등 체크 — 기존 보고서 있으면 그대로 반환</li>
 *   <li>새 지출보고서 생성(DRAFT) 및 저장</li>
 * </ol>
 *
 * <h3>워크플로</h3>
 * <pre>
 * DRAFT ──submit──▶ SUBMITTED ──approve──▶ APPROVED (부서예산 소진액 갱신)
 *                       │
 *                    reject
 *                       │
 *                       ▼
 *                   REJECTED ──submit──▶ SUBMITTED (재제출)
 * </pre>
 */
@Service
public class ExpenseWorkflowService
        implements CreateExpenseReportFromCaptureUseCase,
                   SubmitExpenseReportUseCase,
                   ApproveExpenseReportUseCase,
                   RejectExpenseReportUseCase,
                   QueryBudgetUtilizationUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExpenseWorkflowService.class);

    private final LoadExpenseReportPort loadExpenseReportPort;
    private final SaveExpenseReportPort saveExpenseReportPort;
    private final LoadDepartmentBudgetPort loadDepartmentBudgetPort;
    private final UpdateDepartmentBudgetPort updateDepartmentBudgetPort;
    private final LoadExpenseReceiptPort loadExpenseReceiptPort;
    private final ReceiptOcrProperties receiptOcrProperties;

    public ExpenseWorkflowService(LoadExpenseReportPort loadExpenseReportPort,
                                   SaveExpenseReportPort saveExpenseReportPort,
                                   LoadDepartmentBudgetPort loadDepartmentBudgetPort,
                                   UpdateDepartmentBudgetPort updateDepartmentBudgetPort,
                                   LoadExpenseReceiptPort loadExpenseReceiptPort,
                                   ReceiptOcrProperties receiptOcrProperties) {
        this.loadExpenseReportPort = loadExpenseReportPort;
        this.saveExpenseReportPort = saveExpenseReportPort;
        this.loadDepartmentBudgetPort = loadDepartmentBudgetPort;
        this.updateDepartmentBudgetPort = updateDepartmentBudgetPort;
        this.loadExpenseReceiptPort = loadExpenseReceiptPort;
        this.receiptOcrProperties = receiptOcrProperties;
    }

    /**
     * 매입 확정 이벤트로부터 DRAFT 지출보고서 자동 생성.
     *
     * <p>멱등 키: {@code captureId} — 같은 매입 재요청 시 기존 보고서 반환.
     */
    @Override
    @Transactional
    public ExpenseReport createFromCapture(CreateExpenseReportCommand command) {
        // 멱등 체크: 동일 captureId 이미 존재하면 그대로 반환
        return loadExpenseReportPort.findByCaptureId(command.captureId())
                .orElseGet(() -> {
                    String reportId = "RPT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
                    ExpenseReport report = ExpenseReport.createFromCapture(
                            reportId,
                            command.captureId(),
                            command.authorizationId(),
                            command.cardId(),
                            command.cardAccountId(),
                            command.organizationId(),
                            command.departmentId(),
                            command.holderUserId(),
                            command.amount(),
                            command.merchantName(),
                            command.capturedAt() != null ? command.capturedAt() : Instant.now()
                    );
                    ExpenseReport saved = saveExpenseReportPort.save(report);
                    log.info("지출보고서 DRAFT 생성. reportId={}, captureId={}, amount={}",
                            saved.getReportId(), saved.getCaptureId(), saved.getAmount());
                    return saved;
                });
    }

    /**
     * 임직원이 영수증·카테고리·메모를 첨부해 제출 (DRAFT 또는 REJECTED → SUBMITTED).
     */
    @Override
    @Transactional
    public ExpenseReport submit(SubmitExpenseReportCommand command) {
        ExpenseReport report = loadOrThrow(command.reportId());
        report.submit(command.receiptUrl(), command.expenseCategory(), command.memo());
        ExpenseReport saved = saveExpenseReportPort.save(report);
        log.info("지출보고서 제출. reportId={}, status={}", saved.getReportId(), saved.getStatus());
        return saved;
    }

    /**
     * 관리자 승인 (SUBMITTED → APPROVED). 부서 예산 소진액 갱신.
     *
     * <p><b>영수증 대사 게이트(ADR 0036)</b>: 영수증이 첨부돼 있으면 최신 영수증이 MATCHED 여야 승인
     * 통과 — MISMATCHED·NEEDS_REVIEW·EXTRACTED 는 422 로 거절한다. 영수증이 없으면 기존 경로 그대로
     * 통과(점진 도입 — 전면 강제는 조직별 정책 플래그가 필요한 별도 결정).
     */
    @Override
    @Transactional
    public ExpenseReport approve(ApproveExpenseReportCommand command) {
        ExpenseReport report = loadOrThrow(command.reportId());
        var latestReceipt = loadExpenseReceiptPort.findLatestByReportId(report.getReportId());
        if (latestReceipt.isEmpty()) {
            // 전면 강제(required=true)면 미첨부 자체가 거절 사유 — 점진 도입이면 기존 경로 그대로.
            if (Boolean.TRUE.equals(receiptOcrProperties.required())) {
                throw new BusinessException(ErrorCode.CARD_RECEIPT_NOT_MATCHED,
                        "영수증이 첨부되지 않아 승인할 수 없습니다(전면 강제): " + report.getReportId());
            }
        } else if (latestReceipt.get().getStatus() != ExpenseReceiptStatus.MATCHED) {
            throw new BusinessException(ErrorCode.CARD_RECEIPT_NOT_MATCHED,
                    "영수증 대사 미통과(" + latestReceipt.get().getStatus() + ") — "
                            + latestReceipt.get().getMatchNote());
        }
        report.approve(command.reviewerId());
        ExpenseReport saved = saveExpenseReportPort.save(report);

        // 부서 예산 소진액 갱신 (departmentId 가 있을 때만)
        if (saved.getDepartmentId() != null && !saved.getDepartmentId().isBlank()) {
            Instant capturedAt = saved.getCapturedAt();
            var zonedCaptured = capturedAt.atZone(ZoneOffset.UTC);
            updateDepartmentBudgetPort.incrementApprovedAmount(
                    saved.getOrganizationId(),
                    saved.getDepartmentId(),
                    zonedCaptured.getYear(),
                    zonedCaptured.getMonthValue(),
                    saved.getAmount());
        }

        log.info("지출보고서 승인. reportId={}, reviewerId={}", saved.getReportId(), command.reviewerId());
        return saved;
    }

    /**
     * 관리자 반려 (SUBMITTED → REJECTED).
     */
    @Override
    @Transactional
    public ExpenseReport reject(RejectExpenseReportCommand command) {
        ExpenseReport report = loadOrThrow(command.reportId());
        report.reject(command.reviewerId(), command.rejectReason());
        ExpenseReport saved = saveExpenseReportPort.save(report);
        log.info("지출보고서 반려. reportId={}, reviewerId={}, reason={}",
                saved.getReportId(), command.reviewerId(), command.rejectReason());
        return saved;
    }

    /**
     * 부서 예산 소진율 조회.
     *
     * <p>예산 레코드가 없으면 소진율 0 을 반환한다(미등록 부서는 예산 없음으로 취급).
     */
    @Override
    @Transactional(readOnly = true)
    public BudgetUtilization getUtilization(Long organizationId, String departmentId,
                                             int year, int month) {
        return loadDepartmentBudgetPort.findBudget(organizationId, departmentId, year, month)
                .map(rec -> {
                    BigDecimal utilization = rec.totalBudget().signum() == 0
                            ? BigDecimal.ZERO
                            : rec.approvedAmount()
                                    .multiply(new BigDecimal("100"))
                                    .divide(rec.totalBudget(), 2, RoundingMode.HALF_UP);
                    return new BudgetUtilization(
                            rec.organizationId(),
                            rec.departmentId(),
                            rec.year(),
                            rec.month(),
                            rec.totalBudget(),
                            rec.approvedAmount(),
                            utilization);
                })
                .orElse(new BudgetUtilization(
                        organizationId, departmentId, year, month,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
    }

    // ── private ──

    private ExpenseReport loadOrThrow(String reportId) {
        return loadExpenseReportPort.findByReportId(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CARD_NOT_FOUND,
                        "지출보고서를 찾을 수 없습니다: " + reportId));
    }
}
