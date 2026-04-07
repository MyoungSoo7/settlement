package github.lms.lemuel.product.application.port.out;

import github.lms.lemuel.product.domain.ProductOption;
import github.lms.lemuel.product.domain.ProductOptionValue;
import github.lms.lemuel.product.domain.ProductVariant;

public interface SaveProductVariantPort {

    ProductOption saveOption(ProductOption option);

    ProductOptionValue saveOptionValue(ProductOptionValue optionValue);

    ProductVariant saveVariant(ProductVariant variant);

    void deleteOption(Long optionId);

    void deleteOptionValue(Long optionValueId);
}
