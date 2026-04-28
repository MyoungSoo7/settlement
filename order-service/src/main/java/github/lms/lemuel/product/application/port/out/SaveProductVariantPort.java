package github.lms.lemuel.product.application.port.out;

import github.lms.lemuel.product.domain.ProductVariant;

public interface SaveProductVariantPort {
    ProductVariant save(ProductVariant variant);
}
