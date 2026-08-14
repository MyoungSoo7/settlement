package github.lms.lemuel.tax.adapter.in.web;

import github.lms.lemuel.tax.application.port.in.GetTaxInvoiceScanUseCase;
import github.lms.lemuel.tax.application.port.in.ReviewTaxInvoiceScanUseCase;
import github.lms.lemuel.tax.domain.scan.TaxInvoiceScanStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 세금계산서 스캔 관리자 API — 리뷰 큐 조회·반려·재대사 (경로 게이트: {@code /admin/tax/**} = ADMIN·MANAGER).
 *
 * <p>OCR 은 사람의 판단을 대체하지 않는다. 저신뢰·산술 불일치·미매칭 건은 이 큐로 흘러 사람이 종결한다.
 */
@Tag(name = "Tax Invoice Scan (Admin)", description = "세금계산서 스캔 리뷰 큐")
@RestController
@RequestMapping("/admin/tax/scans")
public class TaxInvoiceScanAdminController {

    private static final int DEFAULT_LIMIT = 50;

    private final GetTaxInvoiceScanUseCase getUseCase;
    private final ReviewTaxInvoiceScanUseCase reviewUseCase;

    public TaxInvoiceScanAdminController(GetTaxInvoiceScanUseCase getUseCase,
                                         ReviewTaxInvoiceScanUseCase reviewUseCase) {
        this.getUseCase = getUseCase;
        this.reviewUseCase = reviewUseCase;
    }

    @Operation(summary = "리뷰 큐 조회(상태별)")
    @GetMapping
    public ResponseEntity<List<TaxInvoiceScanView>> queue(
            @RequestParam(defaultValue = "MISMATCHED") TaxInvoiceScanStatus status,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        return ResponseEntity.ok(getUseCase.byStatus(status, limit).stream()
                .map(TaxInvoiceScanView::of)
                .toList());
    }

    @Operation(summary = "반려(종결)")
    @PostMapping("/{scanId}/reject")
    public ResponseEntity<TaxInvoiceScanView> reject(@PathVariable Long scanId,
                                                     @RequestBody RejectRequest request) {
        return ResponseEntity.ok(TaxInvoiceScanView.of(
                reviewUseCase.reject(scanId, request == null ? null : request.note())));
    }

    @Operation(summary = "재대사")
    @PostMapping("/{scanId}/rematch")
    public ResponseEntity<TaxInvoiceScanView> rematch(@PathVariable Long scanId) {
        return ResponseEntity.ok(TaxInvoiceScanView.of(reviewUseCase.rematch(scanId)));
    }

    public record RejectRequest(String note) {
    }
}
