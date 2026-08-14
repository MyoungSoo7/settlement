package github.lms.lemuel.card.adapter.in.web;

import github.lms.lemuel.card.application.port.in.AttachExpenseReceiptUseCase;
import github.lms.lemuel.card.application.port.in.AttachExpenseReceiptUseCase.AttachReceiptCommand;
import github.lms.lemuel.card.application.port.in.GetExpenseReceiptUseCase;
import github.lms.lemuel.card.application.port.in.ReviewExpenseReceiptUseCase;
import github.lms.lemuel.card.application.port.in.ReviewExpenseReceiptUseCase.ReviewReceiptCommand;
import github.lms.lemuel.card.domain.ExpenseReceipt;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 영수증 첨부·조회·리뷰 REST 어댑터 (ADR 0036).
 *
 * <p>경로: {@code /internal/api/v1/**} — 지출 워크플로({@code ExpenseWorkflowAdapter})와 같은 내부망
 * 전용 관례. API Gateway 미노출.
 *
 * <h3>엔드포인트</h3>
 * <ul>
 *   <li>{@code POST /internal/api/v1/expense-reports/{reportId}/receipts} — 멀티파트 업로드 → OCR → 자동 대사</li>
 *   <li>{@code GET /internal/api/v1/expense-reports/{reportId}/receipts/latest} — 최신 영수증(승인 게이트와 같은 기준)</li>
 *   <li>{@code POST /internal/api/v1/expense-receipts/{receiptId}/review} — NEEDS_REVIEW 육안 리뷰 종결</li>
 * </ul>
 *
 * <p>소유권: 업로더 검증은 유스케이스가 보고서 소지자(holderUserId)와 대조한다 (403).
 */
@RestController
public class ExpenseReceiptAdapter {

    private final AttachExpenseReceiptUseCase attachExpenseReceiptUseCase;
    private final GetExpenseReceiptUseCase getExpenseReceiptUseCase;
    private final ReviewExpenseReceiptUseCase reviewExpenseReceiptUseCase;

    public ExpenseReceiptAdapter(AttachExpenseReceiptUseCase attachExpenseReceiptUseCase,
                                 GetExpenseReceiptUseCase getExpenseReceiptUseCase,
                                 ReviewExpenseReceiptUseCase reviewExpenseReceiptUseCase) {
        this.attachExpenseReceiptUseCase = attachExpenseReceiptUseCase;
        this.getExpenseReceiptUseCase = getExpenseReceiptUseCase;
        this.reviewExpenseReceiptUseCase = reviewExpenseReceiptUseCase;
    }

    /**
     * 영수증 업로드 → OCR 추출 → 매입 자동 대사. 같은 파일 재업로드는 기존 영수증 반환(멱등).
     */
    @PostMapping("/internal/api/v1/expense-reports/{reportId}/receipts")
    public ResponseEntity<ExpenseReceiptResponse> attach(
            @PathVariable String reportId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("uploaderUserId") Long uploaderUserId) {

        ExpenseReceipt receipt = attachExpenseReceiptUseCase.attach(new AttachReceiptCommand(
                reportId, uploaderUserId, file.getOriginalFilename(), file.getContentType(),
                bytesOf(file)));
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(receipt));
    }

    /**
     * 보고서의 최신 영수증 조회 — 승인 게이트가 판정하는 것과 같은 기준.
     */
    @GetMapping("/internal/api/v1/expense-reports/{reportId}/receipts/latest")
    public ResponseEntity<ExpenseReceiptResponse> latest(@PathVariable String reportId) {
        return getExpenseReceiptUseCase.latestForReport(reportId)
                .map(receipt -> ResponseEntity.ok(toResponse(receipt)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * NEEDS_REVIEW 영수증 육안 리뷰 종결 (MATCHED/MISMATCHED).
     */
    @PostMapping("/internal/api/v1/expense-receipts/{receiptId}/review")
    public ResponseEntity<ExpenseReceiptResponse> review(
            @PathVariable Long receiptId,
            @Valid @RequestBody ReviewRequest request) {

        ExpenseReceipt receipt = reviewExpenseReceiptUseCase.review(new ReviewReceiptCommand(
                receiptId, request.reviewerId(), request.matched(), request.note()));
        return ResponseEntity.ok(toResponse(receipt));
    }

    private static byte[] bytesOf(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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

    // ── DTO ──

    /**
     * 리뷰 요청.
     *
     * @param reviewerId 리뷰어 userId
     * @param matched    true=대사 확정 / false=반려
     * @param note       육안 대조 근거
     */
    public record ReviewRequest(
            @NotNull Long reviewerId,
            @NotNull Boolean matched,
            String note
    ) {
    }

    /**
     * 영수증 응답 — 금액·신뢰도는 십진 문자열(DATA-STANDARD N5), 파일 본문은 싣지 않는다.
     */
    public record ExpenseReceiptResponse(
            Long id,
            String reportId,
            String captureId,
            String status,
            String merchantName,
            LocalDate transactionDate,
            String totalAmount,
            String confidence,
            String matchNote,
            String ocrModel,
            String fileName,
            Long reviewedBy,
            Instant createdAt
    ) {
    }
}
