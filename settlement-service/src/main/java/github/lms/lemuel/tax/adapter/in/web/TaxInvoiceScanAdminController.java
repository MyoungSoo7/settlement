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

    /**
     * 기본 큐 = <b>사람 손이 필요한 상태 전부</b>.
     *
     * <p>종전 기본값은 {@code MISMATCHED} 하나였다. 저신뢰 판독이 자동 대사를 건너뛰고
     * {@code EXTRACTED} 에 남게 되면서, 그 건들이 기본 화면에 보이지 않는 사각지대가 생겼다 —
     * 보류시켜 놓고 아무도 안 보면 고치지 않은 것과 같다.
     *
     * <p>종결 상태({@code MATCHED}·{@code REJECTED})는 넣지 않는다. 넣는 순간 큐가 이력 조회가
     * 되어 정작 조치가 필요한 건이 묻힌다.
     */
    static final List<TaxInvoiceScanStatus> REVIEW_QUEUE = List.of(
            TaxInvoiceScanStatus.EXTRACTED,
            TaxInvoiceScanStatus.MISMATCHED,
            TaxInvoiceScanStatus.UNMATCHED);

    private final GetTaxInvoiceScanUseCase getUseCase;
    private final ReviewTaxInvoiceScanUseCase reviewUseCase;

    public TaxInvoiceScanAdminController(GetTaxInvoiceScanUseCase getUseCase,
                                         ReviewTaxInvoiceScanUseCase reviewUseCase) {
        this.getUseCase = getUseCase;
        this.reviewUseCase = reviewUseCase;
    }

    /**
     * 리뷰 큐 조회 — {@code ?status=A&status=B} 로 여러 상태를 한 번에 받는다.
     *
     * <p>생략하면 {@link #REVIEW_QUEUE}(사람 손이 필요한 3종)를 연다.
     */
    @Operation(summary = "리뷰 큐 조회(상태 복수 지정 가능, 기본은 사람 손이 필요한 3종)")
    @GetMapping
    public ResponseEntity<List<TaxInvoiceScanView>> queue(
            @RequestParam(required = false) List<TaxInvoiceScanStatus> status,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        List<TaxInvoiceScanStatus> statuses =
                (status == null || status.isEmpty()) ? REVIEW_QUEUE : status;
        return ResponseEntity.ok(getUseCase.byStatuses(statuses, limit).stream()
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
