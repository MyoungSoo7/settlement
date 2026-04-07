package github.lms.lemuel.product.adapter.in.web;

import github.lms.lemuel.product.adapter.in.web.request.CreateProductOptionRequest;
import github.lms.lemuel.product.adapter.in.web.request.CreateProductVariantRequest;
import github.lms.lemuel.product.adapter.in.web.request.UpdateVariantPriceRequest;
import github.lms.lemuel.product.adapter.in.web.request.UpdateVariantStockRequest;
import github.lms.lemuel.product.adapter.in.web.response.ProductOptionResponse;
import github.lms.lemuel.product.adapter.in.web.response.ProductVariantResponse;
import github.lms.lemuel.product.application.port.in.ProductVariantUseCase;
import github.lms.lemuel.product.domain.ProductOption;
import github.lms.lemuel.product.domain.ProductVariant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/products/{productId}/variants")
@RequiredArgsConstructor
public class ProductVariantController {

    private final ProductVariantUseCase productVariantUseCase;

    @PostMapping("/options")
    public ResponseEntity<ProductOptionResponse> createOption(
            @PathVariable Long productId,
            @RequestBody CreateProductOptionRequest request) {
        ProductOption option = productVariantUseCase.createOption(productId, request.getName(), request.getValues());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProductOptionResponse.from(option));
    }

    @GetMapping("/options")
    public ResponseEntity<List<ProductOptionResponse>> getOptions(@PathVariable Long productId) {
        List<ProductOption> options = productVariantUseCase.getProductOptions(productId);
        return ResponseEntity.ok(options.stream()
                .map(ProductOptionResponse::from)
                .collect(Collectors.toList()));
    }

    @DeleteMapping("/options/{optionId}")
    public ResponseEntity<Void> deleteOption(
            @PathVariable Long productId,
            @PathVariable Long optionId) {
        productVariantUseCase.deleteOption(optionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping
    public ResponseEntity<ProductVariantResponse> createVariant(
            @PathVariable Long productId,
            @RequestBody CreateProductVariantRequest request) {
        ProductVariantUseCase.CreateVariantCommand cmd = new ProductVariantUseCase.CreateVariantCommand(
                productId,
                request.getSku(),
                request.getPrice(),
                request.getStockQuantity() != null ? request.getStockQuantity() : 0,
                request.getOptionValues()
        );
        ProductVariant variant = productVariantUseCase.createVariant(cmd);
        return ResponseEntity.status(HttpStatus.CREATED).body(ProductVariantResponse.from(variant));
    }

    @GetMapping
    public ResponseEntity<List<ProductVariantResponse>> getVariants(@PathVariable Long productId) {
        List<ProductVariant> variants = productVariantUseCase.getProductVariants(productId);
        return ResponseEntity.ok(variants.stream()
                .map(ProductVariantResponse::from)
                .collect(Collectors.toList()));
    }

    @GetMapping("/{variantId}")
    public ResponseEntity<ProductVariantResponse> getVariant(
            @PathVariable Long productId,
            @PathVariable Long variantId) {
        ProductVariant variant = productVariantUseCase.getVariant(variantId);
        return ResponseEntity.ok(ProductVariantResponse.from(variant));
    }

    @PatchMapping("/{variantId}/price")
    public ResponseEntity<ProductVariantResponse> updatePrice(
            @PathVariable Long productId,
            @PathVariable Long variantId,
            @RequestBody UpdateVariantPriceRequest request) {
        ProductVariant variant = productVariantUseCase.updateVariantPrice(variantId, request.getPrice());
        return ResponseEntity.ok(ProductVariantResponse.from(variant));
    }

    @PatchMapping("/{variantId}/stock")
    public ResponseEntity<ProductVariantResponse> updateStock(
            @PathVariable Long productId,
            @PathVariable Long variantId,
            @RequestBody UpdateVariantStockRequest request) {
        ProductVariant variant = productVariantUseCase.updateVariantStock(variantId, request.getStockQuantity());
        return ResponseEntity.ok(ProductVariantResponse.from(variant));
    }

    @PatchMapping("/{variantId}/deactivate")
    public ResponseEntity<Void> deactivate(
            @PathVariable Long productId,
            @PathVariable Long variantId) {
        productVariantUseCase.deactivateVariant(variantId);
        return ResponseEntity.noContent().build();
    }
}
