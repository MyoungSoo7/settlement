package github.lms.lemuel.tax.adapter.in.web;

import github.lms.lemuel.tax.application.TaxPostingResult;
import github.lms.lemuel.tax.application.port.in.GetTaxInvoiceUseCase;
import github.lms.lemuel.tax.application.port.in.GetTaxReconciliationUseCase;
import github.lms.lemuel.tax.application.port.in.IssueTaxInvoiceUseCase;
import github.lms.lemuel.tax.application.port.in.PostSettlementTaxUseCase;
import github.lms.lemuel.tax.domain.TaxInvoice;
import github.lms.lemuel.tax.domain.TaxReconciliation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * 세무 산출물 운영자 콘솔 (Seed B2) — 세무 전표 전기·세금계산서 발행·3자 대사·조회.
 *
 * <p>인가: {@code /admin/tax/**} 는 SecurityConfig 가 ADMIN/MANAGER 로 게이트한다. 그 위에 {@code sellerId}
 * IDOR 방지(2026-07-24, ADR 0029 후속 수정) — {@code TaxContextResolver} 가 요청 sellerId 와 정산의 실제
 * 소유 셀러를 대조해 불일치 시 {@link AccessDeniedException} 을 던진다. shared-common
 * {@code GlobalExceptionHandler} 에는 {@link AccessDeniedException} 전용 매핑이 없어 catch-all(500)로
 * 새므로, 이 컨트롤러 로컬 핸들러로 403 을 명시한다(로컬 핸들러가 advice 보다 우선 —
 * {@code SettlementSearchController} 의 날짜 파라미터 로컬 핸들러와 동형, {@code TaxInvoiceSellerController}
 * 의 IDOR 403 의도와 동형).
 */
@Tag(name = "Settlement Tax Deliverables", description = "정산 연계 세무 전표·세금계산서·대사 운영자 콘솔")
@RestController
@RequestMapping("/admin/tax/settlements/{settlementId}")
public class SettlementTaxAdminController {

    private final PostSettlementTaxUseCase postTaxUseCase;
    private final IssueTaxInvoiceUseCase issueInvoiceUseCase;
    private final GetTaxReconciliationUseCase reconciliationUseCase;
    private final GetTaxInvoiceUseCase getInvoiceUseCase;

    public SettlementTaxAdminController(PostSettlementTaxUseCase postTaxUseCase,
                                        IssueTaxInvoiceUseCase issueInvoiceUseCase,
                                        GetTaxReconciliationUseCase reconciliationUseCase,
                                        GetTaxInvoiceUseCase getInvoiceUseCase) {
        this.postTaxUseCase = postTaxUseCase;
        this.issueInvoiceUseCase = issueInvoiceUseCase;
        this.reconciliationUseCase = reconciliationUseCase;
        this.getInvoiceUseCase = getInvoiceUseCase;
    }

    @Operation(summary = "세무 전표 전기(부가세·원천징수 예수)")
    @PostMapping("/post")
    public ResponseEntity<TaxPostingView> post(@PathVariable Long settlementId,
                                               @RequestParam Long sellerId) {
        TaxPostingResult result = postTaxUseCase.postForSettlement(settlementId, sellerId);
        return ResponseEntity.ok(TaxPostingView.of(result));
    }

    @Operation(summary = "세금계산서 발행")
    @PostMapping("/invoice")
    public ResponseEntity<InvoiceView> issue(@PathVariable Long settlementId,
                                             @RequestParam Long sellerId) {
        return issueInvoiceUseCase.issueForSettlement(settlementId, sellerId)
                .map(invoice -> ResponseEntity.ok(InvoiceView.of(invoice)))
                .orElse(ResponseEntity.status(409).build());
    }

    @Operation(summary = "세무 3자 대사(계산·세금계산서·원장)")
    @GetMapping("/reconciliation")
    public ResponseEntity<ReconciliationView> reconcile(@PathVariable Long settlementId,
                                                        @RequestParam Long sellerId) {
        TaxReconciliation recon = reconciliationUseCase.reconcile(settlementId, sellerId);
        return ResponseEntity.ok(ReconciliationView.of(recon));
    }

    @Operation(summary = "세금계산서 조회")
    @GetMapping("/invoice")
    public ResponseEntity<InvoiceView> getInvoice(@PathVariable Long settlementId) {
        return getInvoiceUseCase.bySettlementId(settlementId)
                .map(invoice -> ResponseEntity.ok(InvoiceView.of(invoice)))
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "세금계산서 PDF 다운로드")
    @GetMapping("/invoice.pdf")
    public ResponseEntity<byte[]> getInvoicePdf(@PathVariable Long settlementId) {
        return getInvoiceUseCase.renderPdf(settlementId)
                .map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .body(bytes))
                .orElse(ResponseEntity.notFound().build());
    }

    public record TaxPostingView(String outcome, int entriesPosted, BigDecimal vatAmount,
                                 BigDecimal withholdingAmount) {
        static TaxPostingView of(TaxPostingResult r) {
            return new TaxPostingView(r.outcome().name(), r.entriesPosted(),
                    r.calculation() == null ? null : r.calculation().vatAmount(),
                    r.calculation() == null ? null : r.calculation().withholdingAmount());
        }
    }

    public record InvoiceView(Long settlementId, Long sellerId, String issueNumber,
                              BigDecimal supplyAmount, BigDecimal taxAmount, BigDecimal totalAmount,
                              String issueDate) {
        static InvoiceView of(TaxInvoice i) {
            return new InvoiceView(i.getSettlementId(), i.getSellerId(), i.getIssueNumber(),
                    i.getSupplyAmount(), i.getTaxAmount(), i.getTotalAmount(), i.getIssueDate().toString());
        }
    }

    public record ReconciliationView(boolean matched, boolean ledgerBalanced,
                                     BigDecimal ledgerVatAccrued, BigDecimal actualWithholdingDeducted,
                                     List<CheckView> checks) {
        static ReconciliationView of(TaxReconciliation r) {
            return new ReconciliationView(r.matched(), r.ledgerBalanced(),
                    r.ledgerVatAccrued(), r.actualWithholdingDeducted(),
                    r.checks().stream().map(c -> new CheckView(
                            c.name(), c.expected(), c.actual(), c.passed())).toList());
        }
    }

    public record CheckView(String name, BigDecimal expected, BigDecimal actual, boolean passed) {
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Void> handleOwnershipViolation(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
}
