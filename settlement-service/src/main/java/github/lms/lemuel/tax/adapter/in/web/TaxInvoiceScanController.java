package github.lms.lemuel.tax.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.tax.application.port.in.ExtractTaxInvoiceScanUseCase;
import github.lms.lemuel.tax.application.port.in.ExtractTaxInvoiceScanUseCase.UploadTaxInvoiceScanCommand;
import github.lms.lemuel.tax.application.port.in.GetTaxInvoiceScanUseCase;
import github.lms.lemuel.tax.domain.scan.TaxInvoiceScan;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 세금계산서 스캔본 업로드·조회 API (셀러).
 *
 * <p><b>IDOR 방지</b>: 업로더 셀러는 요청 파라미터가 아니라 <b>JWT 주체(userId)</b>에서만 파생한다.
 * 조회는 스캔 소유자와 주체를 대조해 불일치 시 403(ADMIN/MANAGER 는 우회).
 *
 * <p><b>PII</b>: 응답의 사업자등록번호는 마스킹된 형태로만 나간다({@code 101-81-*****}).
 */
@Tag(name = "Tax Invoice Scan (Seller)", description = "세금계산서 스캔 업로드·조회 (AI OCR)")
@RestController
@RequestMapping("/api/tax-invoices/scans")
public class TaxInvoiceScanController {

    private final ExtractTaxInvoiceScanUseCase extractUseCase;
    private final GetTaxInvoiceScanUseCase getUseCase;

    public TaxInvoiceScanController(ExtractTaxInvoiceScanUseCase extractUseCase,
                                    GetTaxInvoiceScanUseCase getUseCase) {
        this.extractUseCase = extractUseCase;
        this.getUseCase = getUseCase;
    }

    @Operation(summary = "스캔본 업로드 → OCR 추출 → 발행분 자동 대사")
    @PostMapping
    public ResponseEntity<TaxInvoiceScanView> upload(@RequestParam("file") MultipartFile file,
                                                     Authentication auth) {
        Long sellerId = requireUserId(auth);
        TaxInvoiceScan scan = extractUseCase.extract(new UploadTaxInvoiceScanCommand(
                sellerId, file.getOriginalFilename(), file.getContentType(), bytesOf(file)));
        return ResponseEntity.status(HttpStatus.CREATED).body(TaxInvoiceScanView.of(scan));
    }

    @Operation(summary = "스캔 조회(본인 소유)")
    @GetMapping("/{scanId}")
    public ResponseEntity<TaxInvoiceScanView> get(@PathVariable Long scanId, Authentication auth) {
        return getUseCase.byId(scanId)
                .map(scan -> {
                    assertOwnership(scan, auth);
                    return ResponseEntity.ok(TaxInvoiceScanView.of(scan));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private void assertOwnership(TaxInvoiceScan scan, Authentication auth) {
        if (isPrivileged(auth)) {
            return;
        }
        Long userId = currentUserId(auth);
        if (userId == null || !userId.equals(scan.getSellerId())) {
            throw new AccessDeniedException("본인이 업로드한 스캔이 아닙니다");
        }
    }

    private static Long requireUserId(Authentication auth) {
        Long userId = currentUserId(auth);
        if (userId == null) {
            throw new AccessDeniedException("업로드하려면 인증이 필요합니다");
        }
        return userId;
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

    private static byte[] bytesOf(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
