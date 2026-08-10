package github.lms.lemuel.card.adapter.in.web;

import github.lms.lemuel.card.application.port.in.ApproveExpenseReportUseCase;
import github.lms.lemuel.card.application.port.in.ApproveExpenseReportUseCase.ApproveExpenseReportCommand;
import github.lms.lemuel.card.application.port.in.QueryBudgetUtilizationUseCase;
import github.lms.lemuel.card.application.port.in.QueryBudgetUtilizationUseCase.BudgetUtilization;
import github.lms.lemuel.card.application.port.in.RejectExpenseReportUseCase;
import github.lms.lemuel.card.application.port.in.RejectExpenseReportUseCase.RejectExpenseReportCommand;
import github.lms.lemuel.card.application.port.in.SubmitExpenseReportUseCase;
import github.lms.lemuel.card.application.port.in.SubmitExpenseReportUseCase.SubmitExpenseReportCommand;
import github.lms.lemuel.card.domain.ExpenseReport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 사후 지출관리 워크플로 REST 어댑터.
 *
 * <p>경로: {@code /internal/api/v1/expense-reports/**}
 *
 * <p>보안: {@code /internal/**} 경로는 내부망에서만 접근 가능. API Gateway 가 외부 미노출.
 *
 * <h3>엔드포인트</h3>
 * <ul>
 *   <li>{@code POST /internal/api/v1/expense-reports/{reportId}/submit} — DRAFT/REJECTED → SUBMITTED</li>
 *   <li>{@code POST /internal/api/v1/expense-reports/{reportId}/approve} — SUBMITTED → APPROVED</li>
 *   <li>{@code POST /internal/api/v1/expense-reports/{reportId}/reject} — SUBMITTED → REJECTED</li>
 *   <li>{@code GET /internal/api/v1/organizations/{orgId}/departments/{deptId}/budget-utilization} — 소진율</li>
 * </ul>
 */
@RestController
@RequestMapping
public class ExpenseWorkflowAdapter {

    private final SubmitExpenseReportUseCase submitExpenseReportUseCase;
    private final ApproveExpenseReportUseCase approveExpenseReportUseCase;
    private final RejectExpenseReportUseCase rejectExpenseReportUseCase;
    private final QueryBudgetUtilizationUseCase queryBudgetUtilizationUseCase;

    public ExpenseWorkflowAdapter(
            SubmitExpenseReportUseCase submitExpenseReportUseCase,
            ApproveExpenseReportUseCase approveExpenseReportUseCase,
            RejectExpenseReportUseCase rejectExpenseReportUseCase,
            QueryBudgetUtilizationUseCase queryBudgetUtilizationUseCase) {
        this.submitExpenseReportUseCase = submitExpenseReportUseCase;
        this.approveExpenseReportUseCase = approveExpenseReportUseCase;
        this.rejectExpenseReportUseCase = rejectExpenseReportUseCase;
        this.queryBudgetUtilizationUseCase = queryBudgetUtilizationUseCase;
    }

    /**
     * 지출보고서 제출 (DRAFT/REJECTED → SUBMITTED).
     */
    @PostMapping("/internal/api/v1/expense-reports/{reportId}/submit")
    public ResponseEntity<ExpenseReportResponse> submit(
            @PathVariable String reportId,
            @Valid @RequestBody SubmitRequest request) {

        ExpenseReport report = submitExpenseReportUseCase.submit(
                new SubmitExpenseReportCommand(
                        reportId,
                        request.receiptUrl(),
                        request.expenseCategory(),
                        request.memo()));
        return ResponseEntity.ok(toResponse(report));
    }

    /**
     * 관리자 승인 (SUBMITTED → APPROVED).
     */
    @PostMapping("/internal/api/v1/expense-reports/{reportId}/approve")
    public ResponseEntity<ExpenseReportResponse> approve(
            @PathVariable String reportId,
            @Valid @RequestBody ApproveRequest request) {

        ExpenseReport report = approveExpenseReportUseCase.approve(
                new ApproveExpenseReportCommand(reportId, request.reviewerId()));
        return ResponseEntity.ok(toResponse(report));
    }

    /**
     * 관리자 반려 (SUBMITTED → REJECTED).
     */
    @PostMapping("/internal/api/v1/expense-reports/{reportId}/reject")
    public ResponseEntity<ExpenseReportResponse> reject(
            @PathVariable String reportId,
            @Valid @RequestBody RejectRequest request) {

        ExpenseReport report = rejectExpenseReportUseCase.reject(
                new RejectExpenseReportCommand(
                        reportId,
                        request.reviewerId(),
                        request.rejectReason()));
        return ResponseEntity.ok(toResponse(report));
    }

    /**
     * 부서 예산 소진율 조회.
     */
    @GetMapping("/internal/api/v1/organizations/{orgId}/departments/{deptId}/budget-utilization")
    public ResponseEntity<BudgetUtilizationResponse> getBudgetUtilization(
            @PathVariable Long orgId,
            @PathVariable String deptId,
            @org.springframework.web.bind.annotation.RequestParam int year,
            @org.springframework.web.bind.annotation.RequestParam int month) {

        BudgetUtilization utilization =
                queryBudgetUtilizationUseCase.getUtilization(orgId, deptId, year, month);
        return ResponseEntity.ok(new BudgetUtilizationResponse(
                utilization.organizationId(),
                utilization.departmentId(),
                utilization.year(),
                utilization.month(),
                utilization.totalBudget().toPlainString(),
                utilization.approvedAmount().toPlainString(),
                utilization.utilizationPercent().toPlainString()));
    }

    // ── 공통 변환 ──

    private ExpenseReportResponse toResponse(ExpenseReport report) {
        return new ExpenseReportResponse(
                report.getReportId(),
                report.getCaptureId(),
                report.getStatus().name(),
                report.getAmount().toPlainString(),
                report.getMerchantName(),
                report.getExpenseCategory(),
                report.getReceiptUrl(),
                report.getMemo(),
                report.getReviewedBy(),
                report.getRejectReason(),
                report.getSubmittedAt(),
                report.getReviewedAt());
    }

    // ── DTO ──

    /**
     * 지출보고서 제출 요청.
     *
     * @param receiptUrl      영수증 URL(optional)
     * @param expenseCategory 경비 카테고리
     * @param memo            메모(optional)
     */
    public record SubmitRequest(
            String receiptUrl,
            @NotBlank String expenseCategory,
            String memo
    ) {
    }

    /**
     * 관리자 승인 요청.
     *
     * @param reviewerId 승인자 userId
     */
    public record ApproveRequest(
            @NotNull Long reviewerId
    ) {
    }

    /**
     * 관리자 반려 요청.
     *
     * @param reviewerId   반려자 userId
     * @param rejectReason 반려 사유
     */
    public record RejectRequest(
            @NotNull Long reviewerId,
            @NotBlank String rejectReason
    ) {
    }

    /**
     * 지출보고서 응답.
     */
    public record ExpenseReportResponse(
            String reportId,
            String captureId,
            String status,
            String amount,
            String merchantName,
            String expenseCategory,
            String receiptUrl,
            String memo,
            Long reviewedBy,
            String rejectReason,
            Instant submittedAt,
            Instant reviewedAt
    ) {
    }

    /**
     * 부서 예산 소진율 응답.
     */
    public record BudgetUtilizationResponse(
            Long organizationId,
            String departmentId,
            int year,
            int month,
            String totalBudget,
            String approvedAmount,
            String utilizationPercent
    ) {
    }
}
