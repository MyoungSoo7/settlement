package github.lms.lemuel.product.adapter.in.web;

import github.lms.lemuel.product.application.port.in.BackfillOptionCatalogUseCase;
import github.lms.lemuel.product.application.port.in.BackfillOptionCatalogUseCase.BackfillReport;
import github.lms.lemuel.product.application.port.in.BackfillVariantSignatureUseCase;
import github.lms.lemuel.product.application.port.in.BackfillVariantSignatureUseCase.SignatureBackfillReport;
import github.lms.lemuel.product.application.port.out.LoadOptionCatalogPort;
import github.lms.lemuel.product.adapter.in.web.response.OptionAxisResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 옵션 카탈로그 관리 API — 표준 축 조회와 레거시 표시명 백필.
 *
 * <p>백필은 되돌릴 필요가 없도록 설계되어 있다(기존 데이터 무변경 + 멱등). 그래도 운영 데이터를
 * 대량으로 만드는 경로이므로 ADMIN 으로 제한한다.
 */
@Tag(name = "Admin Option Catalog", description = "옵션 축/값 카탈로그 관리 API")
@RestController
@RequestMapping("/admin/option-catalog")
@PreAuthorize("hasRole('ADMIN')")
public class AdminOptionCatalogController {

    private final BackfillOptionCatalogUseCase backfillUseCase;
    private final BackfillVariantSignatureUseCase signatureUseCase;
    private final LoadOptionCatalogPort loadCatalogPort;

    public AdminOptionCatalogController(BackfillOptionCatalogUseCase backfillUseCase,
                                        BackfillVariantSignatureUseCase signatureUseCase,
                                        LoadOptionCatalogPort loadCatalogPort) {
        this.backfillUseCase = backfillUseCase;
        this.signatureUseCase = signatureUseCase;
        this.loadCatalogPort = loadCatalogPort;
    }

    @Operation(summary = "표준 옵션 축 목록", description = "등록된 표준 옵션 축을 코드순으로 조회한다.")
    @GetMapping("/axes")
    public ResponseEntity<List<OptionAxisResponse>> getAxes() {
        return ResponseEntity.ok(loadCatalogPort.loadAllAxes().stream()
                .map(OptionAxisResponse::from)
                .toList());
    }

    @Operation(summary = "옵션 카탈로그 백필",
            description = "product_variants.option_name 을 파싱해 축/값 카탈로그를 역생성한다. 멱등 — 재실행해도 안전하다.")
    @PostMapping("/backfill")
    public ResponseEntity<BackfillReport> backfill(
            @Parameter(description = "특정 상품만 처리할 경우 상품 ID (생략 시 전체)")
            @RequestParam(required = false) Long productId) {
        BackfillReport report = productId == null
                ? backfillUseCase.backfillAll()
                : backfillUseCase.backfillProduct(productId);
        return ResponseEntity.ok(report);
    }

    @Operation(summary = "SKU 조합 매핑·서명 백필",
            description = "SKU 를 옵션 값에 매핑하고 조합 서명을 부여한다. 카탈로그 백필이 선행되어야 하며 멱등이다.")
    @PostMapping("/backfill-signatures")
    public ResponseEntity<SignatureBackfillReport> backfillSignatures(
            @Parameter(description = "특정 상품만 처리할 경우 상품 ID (생략 시 전체)")
            @RequestParam(required = false) Long productId) {
        SignatureBackfillReport report = productId == null
                ? signatureUseCase.backfillAll()
                : signatureUseCase.backfillProduct(productId);
        return ResponseEntity.ok(report);
    }
}
