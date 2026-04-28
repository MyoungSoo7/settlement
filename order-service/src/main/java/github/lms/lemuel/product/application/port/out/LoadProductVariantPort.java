package github.lms.lemuel.product.application.port.out;

import github.lms.lemuel.product.domain.ProductVariant;

import java.util.List;
import java.util.Optional;

public interface LoadProductVariantPort {

    Optional<ProductVariant> loadById(Long id);

    Optional<ProductVariant> loadBySku(String sku);

    List<ProductVariant> loadByProductId(Long productId);
}
