package github.lms.lemuel.tax.adapter.in.web;

import github.lms.lemuel.tax.application.port.in.RegisterSellerTaxProfileUseCase;
import github.lms.lemuel.tax.application.port.out.LoadSellerTaxProfilePort;
import github.lms.lemuel.tax.domain.SellerTaxProfile;
import github.lms.lemuel.tax.domain.TaxType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 셀러 세무 프로필 운영자 콘솔 — 등록·정정·조회 (Seed B2).
 *
 * <p>인가: {@code /admin/seller-tax-profiles/**} 는 SecurityConfig 가 ADMIN/MANAGER 로 게이트한다(셀러
 * 식별자를 관리자 입력으로 받으므로 권한 게이트로 IDOR 방지). 사업자등록번호는 조회 시 마스킹만 노출한다.
 */
@Tag(name = "Seller Tax Profile Registry", description = "셀러 세무유형 등록·정정 운영자 콘솔")
@RestController
@RequestMapping("/admin/seller-tax-profiles")
public class SellerTaxProfileAdminController {

    private final RegisterSellerTaxProfileUseCase registerUseCase;
    private final LoadSellerTaxProfilePort loadPort;

    public SellerTaxProfileAdminController(RegisterSellerTaxProfileUseCase registerUseCase,
                                           LoadSellerTaxProfilePort loadPort) {
        this.registerUseCase = registerUseCase;
        this.loadPort = loadPort;
    }

    @Operation(summary = "셀러 세무 프로필 등록(신규 또는 정정 upsert)")
    @PostMapping
    public ResponseEntity<TaxProfileView> register(@RequestBody UpsertRequest request) {
        SellerTaxProfile saved = registerUseCase.register(
                request.sellerId(), TaxType.fromString(request.taxType()), request.businessRegNo());
        return ResponseEntity.ok(TaxProfileView.of(saved));
    }

    @Operation(summary = "셀러 세무 프로필 정정")
    @PutMapping("/{sellerId}")
    public ResponseEntity<TaxProfileView> change(@PathVariable Long sellerId,
                                                 @RequestBody ChangeRequest request) {
        SellerTaxProfile saved = registerUseCase.register(
                sellerId, TaxType.fromString(request.taxType()), request.businessRegNo());
        return ResponseEntity.ok(TaxProfileView.of(saved));
    }

    @Operation(summary = "셀러 세무 프로필 조회(사업자등록번호 마스킹)")
    @GetMapping("/{sellerId}")
    public ResponseEntity<TaxProfileView> get(@PathVariable Long sellerId) {
        return loadPort.findBySellerId(sellerId)
                .map(profile -> ResponseEntity.ok(TaxProfileView.of(profile)))
                .orElse(ResponseEntity.notFound().build());
    }

    public record UpsertRequest(Long sellerId, String taxType, String businessRegNo) {
    }

    public record ChangeRequest(String taxType, String businessRegNo) {
    }

    public record TaxProfileView(Long sellerId, String taxType, String businessRegNo,
                                 LocalDateTime updatedAt) {
        static TaxProfileView of(SellerTaxProfile profile) {
            return new TaxProfileView(profile.getSellerId(), profile.getTaxType().name(),
                    profile.maskedBusinessRegNo(), profile.getUpdatedAt());
        }
    }
}
