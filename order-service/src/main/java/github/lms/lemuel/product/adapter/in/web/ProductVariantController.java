package github.lms.lemuel.product.adapter.in.web;

import github.lms.lemuel.product.application.port.in.CreateProductVariantUseCase;
import github.lms.lemuel.product.application.port.in.DecreaseVariantStockUseCase;
import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.domain.ProductVariant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Tag(name = "Product Variants", description = "상품 옵션(SKU) 관리 + 재고 차감")
@RestController
@RequestMapping("/products/{productId}/variants")
public class ProductVariantController {

    private final CreateProductVariantUseCase createUseCase;
    private final DecreaseVariantStockUseCase decreaseStockUseCase;
    private final LoadProductVariantPort loadPort;

    public ProductVariantController(CreateProductVariantUseCase createUseCase,
                                     DecreaseVariantStockUseCase decreaseStockUseCase,
                                     LoadProductVariantPort loadPort) {
        this.createUseCase = createUseCase;
        this.decreaseStockUseCase = decreaseStockUseCase;
        this.loadPort = loadPort;
    }

    @Operation(summary = "옵션(SKU) 생성")
    @PostMapping
    public ResponseEntity<VariantResponse> create(@PathVariable Long productId,
                                                   @RequestBody CreateVariantRequest request) {
        ProductVariant variant = createUseCase.create(productId, request.sku(),
                request.optionName(), request.additionalPrice(), request.initialStock());
        return ResponseEntity.ok(VariantResponse.from(variant));
    }

    @Operation(summary = "특정 상품의 옵션(SKU) 목록")
    @GetMapping
    public ResponseEntity<List<VariantResponse>> list(@PathVariable Long productId) {
        return ResponseEntity.ok(loadPort.loadByProductId(productId).stream()
                .map(VariantResponse::from).toList());
    }

    @Operation(summary = "옵션(SKU) 재고 차감",
            description = "Optimistic Lock 충돌 시 자동 재시도. 한계 초과 시 409 가능 — 운영팀 알람.")
    @PostMapping("/{variantId}/decrease-stock")
    public ResponseEntity<VariantResponse> decreaseStock(@PathVariable Long productId,
                                                          @PathVariable Long variantId,
                                                          @RequestBody DecreaseStockRequest request) {
        ProductVariant updated = decreaseStockUseCase.decrease(variantId, request.quantity());
        return ResponseEntity.ok(VariantResponse.from(updated));
    }

    public record CreateVariantRequest(
            @NotBlank String sku,
            @NotBlank String optionName,
            BigDecimal additionalPrice,
            @Min(0) int initialStock) {}

    public record DecreaseStockRequest(@Min(1) int quantity) {}

    public record VariantResponse(Map<String, Object> variant) {
        static VariantResponse from(ProductVariant v) {
            return new VariantResponse(Map.of(
                    "id", v.getId(),
                    "productId", v.getProductId(),
                    "sku", v.getSku(),
                    "optionName", v.getOptionName(),
                    "additionalPrice", v.getAdditionalPrice(),
                    "discountPrice", v.getDiscountPrice(),
                    "discountRate", v.getDiscountRate(),
                    "stockQuantity", v.getStockQuantity(),
                    "version", v.getVersion(),
                    "status", v.getStatus().name()
            ));
        }
    }
}
