package github.lms.lemuel.tax.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.tax.application.port.in.GetTaxInvoiceUseCase;
import github.lms.lemuel.tax.domain.TaxInvoice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 세금계산서 셀러 다운로드 API — 셀러가 본인 정산의 세금계산서를 조회·다운로드한다.
 *
 * <p><b>IDOR 방지</b>: 대상 정산의 세금계산서 소유 셀러가 JWT 주체(userId)와 일치하는지 대조해 불일치 시
 * 403 을 던진다(요청 파라미터의 sellerId 를 신뢰하지 않는다). ADMIN/MANAGER 는 소유권 검사를 우회한다.
 */
@Tag(name = "Tax Invoice (Seller)", description = "세금계산서 셀러 조회·다운로드")
@RestController
@RequestMapping("/api/tax-invoices/settlement/{settlementId}")
public class TaxInvoiceSellerController {

    private final GetTaxInvoiceUseCase getInvoiceUseCase;

    public TaxInvoiceSellerController(GetTaxInvoiceUseCase getInvoiceUseCase) {
        this.getInvoiceUseCase = getInvoiceUseCase;
    }

    @Operation(summary = "세금계산서 조회(본인 소유)")
    @GetMapping
    public ResponseEntity<InvoiceView> get(@PathVariable Long settlementId, Authentication auth) {
        return getInvoiceUseCase.bySettlementId(settlementId)
                .map(invoice -> {
                    assertOwnership(invoice, auth);
                    return ResponseEntity.ok(InvoiceView.of(invoice));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "세금계산서 PDF 다운로드(본인 소유)")
    @GetMapping("/pdf")
    public ResponseEntity<byte[]> getPdf(@PathVariable Long settlementId, Authentication auth) {
        return getInvoiceUseCase.bySettlementId(settlementId)
                .map(invoice -> {
                    assertOwnership(invoice, auth);
                    byte[] pdf = getInvoiceUseCase.renderPdf(settlementId).orElseThrow();
                    return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).body(pdf);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private void assertOwnership(TaxInvoice invoice, Authentication auth) {
        if (isPrivileged(auth)) {
            return;
        }
        Long userId = currentUserId(auth);
        if (userId == null || !userId.equals(invoice.getSellerId())) {
            throw new AccessDeniedException("본인 소유의 세금계산서가 아닙니다");
        }
    }

    private static boolean isPrivileged(Authentication auth) {
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String role = ga.getAuthority();
            if ("ROLE_ADMIN".equals(role) || "ROLE_MANAGER".equals(role)) {
                return true;
            }
        }
        return false;
    }

    private static Long currentUserId(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof AuthPrincipal principal) {
            return principal.userId();
        }
        return null;
    }

    public record InvoiceView(Long settlementId, String issueNumber, BigDecimal supplyAmount,
                              BigDecimal taxAmount, BigDecimal totalAmount, String issueDate) {
        static InvoiceView of(TaxInvoice i) {
            return new InvoiceView(i.getSettlementId(), i.getIssueNumber(), i.getSupplyAmount(),
                    i.getTaxAmount(), i.getTotalAmount(), i.getIssueDate().toString());
        }
    }
}
