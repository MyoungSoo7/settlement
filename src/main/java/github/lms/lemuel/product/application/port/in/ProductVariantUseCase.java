package github.lms.lemuel.product.application.port.in;

import github.lms.lemuel.product.domain.ProductOption;
import github.lms.lemuel.product.domain.ProductVariant;

import java.math.BigDecimal;
import java.util.List;

public interface ProductVariantUseCase {

    ProductOption createOption(Long productId, String name, List<String> values);

    List<ProductOption> getProductOptions(Long productId);

    void deleteOption(Long optionId);

    ProductVariant createVariant(CreateVariantCommand cmd);

    ProductVariant updateVariantPrice(Long variantId, BigDecimal price);

    ProductVariant updateVariantStock(Long variantId, int quantity);

    List<ProductVariant> getProductVariants(Long productId);

    ProductVariant getVariant(Long variantId);

    ProductVariant getVariantBySku(String sku);

    void deactivateVariant(Long variantId);

    record CreateVariantCommand(
            Long productId,
            String sku,
            BigDecimal price,
            int stockQuantity,
            String optionValues
    ) {}
}
