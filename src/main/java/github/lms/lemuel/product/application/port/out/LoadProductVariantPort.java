package github.lms.lemuel.product.application.port.out;

import github.lms.lemuel.product.domain.ProductOption;
import github.lms.lemuel.product.domain.ProductVariant;

import java.util.List;
import java.util.Optional;

public interface LoadProductVariantPort {

    List<ProductOption> findOptionsByProductId(Long productId);

    Optional<ProductOption> findOptionById(Long optionId);

    List<ProductVariant> findVariantsByProductId(Long productId);

    Optional<ProductVariant> findVariantById(Long variantId);

    Optional<ProductVariant> findVariantBySku(String sku);
}
