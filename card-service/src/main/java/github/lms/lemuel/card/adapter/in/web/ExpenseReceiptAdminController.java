package github.lms.lemuel.card.adapter.in.web;

import github.lms.lemuel.card.adapter.in.web.ExpenseReceiptAdapter.ExpenseReceiptResponse;
import github.lms.lemuel.card.application.port.in.GetExpenseReceiptUseCase;
import github.lms.lemuel.card.application.port.in.ReviewExpenseReceiptUseCase;
import github.lms.lemuel.card.application.port.in.ReviewExpenseReceiptUseCase.ReviewReceiptCommand;
import github.lms.lemuel.card.domain.ExpenseReceipt;
import github.lms.lemuel.card.domain.ExpenseReceiptStatus;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 영수증 리뷰 큐 운영자 콘솔 (ADR 0036) — card 최초의 {@code /admin} 표면.
 *
 * <p>기존 영수증 API({@code ExpenseReceiptAdapter})는 내부망 전용({@code /internal/**}, 게이트웨이
 * 미노출)이고 reviewerId 를 본문으로 받는다 — 프론트 리뷰 큐 화면에는 내부망 표면을 노출하지 않고,
 * 이 콘솔이 <b>JWT 주체에서 리뷰어를 파생</b>해 같은 유스케이스를 부른다. shared-common SecurityConfig
 * 가 {@code /admin/expense-receipts/**} 를 ADMIN 으로 게이트하고, gateway 가 card-service 로 라우팅한다.
 */
@Tag(name = "Expense Receipt Review (Admin)", description = "영수증 리뷰 큐 (ADR 0036, 운영자)")
@RestController
@RequestMapping("/admin/expense-receipts")
public class ExpenseReceiptAdminController {

    private final GetExpenseReceiptUseCase getUseCase;
    private final ReviewExpenseReceiptUseCase reviewUseCase;

    public ExpenseReceiptAdminController(GetExpenseReceiptUseCase getUseCase,
                                         ReviewExpenseReceiptUseCase reviewUseCase) {
        this.getUseCase = getUseCase;
        this.reviewUseCase = reviewUseCase;
    }

    @Operation(summary = "영수증 리뷰 큐 — 상태별 목록(최신 우선)",
            description = "기본 NEEDS_REVIEW. 리뷰 큐 화면이 이 목록을 그린다 (settlement tax 스캔 큐 선례).")
    @GetMapping
    public ResponseEntity<List<ExpenseReceiptResponse>> queue(
            @RequestParam(defaultValue = "NEEDS_REVIEW") ExpenseReceiptStatus status,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(getUseCase.byStatus(status, limit).stream()
                .map(ExpenseReceiptAdminController::toResponse)
                .toList());
    }

    @Operation(summary = "NEEDS_REVIEW 영수증 육안 리뷰 종결 (MATCHED/MISMATCHED)",
            description = "리뷰어는 요청이 아니라 JWT 주체에서 파생한다 — 내부망 표면과 달리 감사 추적이 목적이다.")
    @PostMapping("/{receiptId}/review")
    public ResponseEntity<ExpenseReceiptResponse> review(
            @PathVariable Long receiptId,
            @Valid @RequestBody ReviewRequest request,
            Authentication authentication) {
        Long reviewerId = callerUserId(authentication);
        ExpenseReceipt receipt = reviewUseCase.review(new ReviewReceiptCommand(
                receiptId, reviewerId, request.matched(), request.note()));
        return ResponseEntity.ok(toResponse(receipt));
    }

    private static Long callerUserId(Authentication authentication) {
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthPrincipal principal
                && principal.userId() != null) {
            return principal.userId();
        }
        throw new AccessDeniedException("인증 주체에서 사용자 식별자를 확인할 수 없습니다.");
    }

    private static ExpenseReceiptResponse toResponse(ExpenseReceipt receipt) {
        return new ExpenseReceiptResponse(
                receipt.getId(),
                receipt.getReportId(),
                receipt.getCaptureId(),
                receipt.getStatus().name(),
                receipt.getExtracted().merchantName(),
                receipt.getExtracted().transactionDate(),
                receipt.getExtracted().totalAmount().toPlainString(),
                receipt.getExtracted().confidence().toPlainString(),
                receipt.getMatchNote(),
                receipt.getOcrModel(),
                receipt.getFileName(),
                receipt.getReviewedBy(),
                receipt.getCreatedAt());
    }

    /**
     * 리뷰 요청 — 리뷰어는 JWT 주체에서 파생하므로 본문에 없다.
     *
     * @param matched true=대사 확정 / false=반려
     * @param note    육안 대조 근거
     */
    public record ReviewRequest(@NotNull Boolean matched, String note) {
    }
}
