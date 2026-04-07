package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.ProductVariantUseCase;
import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.application.port.out.SaveProductVariantPort;
import github.lms.lemuel.product.domain.ProductOption;
import github.lms.lemuel.product.domain.ProductOptionValue;
import github.lms.lemuel.product.domain.ProductVariant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductVariantService implements ProductVariantUseCase {

    private final LoadProductVariantPort loadProductVariantPort;
    private final SaveProductVariantPort saveProductVariantPort;

    @Override
    @Transactional
    public ProductOption createOption(Long productId, String name, List<String> values) {
        ProductOption option = ProductOption.create(productId, name, 0);
        ProductOption savedOption = saveProductVariantPort.saveOption(option);

        if (values != null) {
            for (int i = 0; i < values.size(); i++) {
                ProductOptionValue optionValue = ProductOptionValue.create(savedOption.getId(), values.get(i), i);
                saveProductVariantPort.saveOptionValue(optionValue);
            }
        }

        // 저장 후 값들을 포함하여 다시 조회
        return loadProductVariantPort.findOptionById(savedOption.getId())
                .orElseThrow(() -> new IllegalStateException("Failed to load saved option"));
    }

    @Override
    public List<ProductOption> getProductOptions(Long productId) {
        return loadProductVariantPort.findOptionsByProductId(productId);
    }

    @Override
    @Transactional
    public void deleteOption(Long optionId) {
        loadProductVariantPort.findOptionById(optionId)
                .orElseThrow(() -> new IllegalArgumentException("Option not found: " + optionId));
        saveProductVariantPort.deleteOption(optionId);
    }

    @Override
    @Transactional
    public ProductVariant createVariant(CreateVariantCommand cmd) {
        ProductVariant variant = ProductVariant.create(
                cmd.productId(),
                cmd.sku(),
                cmd.price(),
                cmd.stockQuantity(),
                cmd.optionValues()
        );
        return saveProductVariantPort.saveVariant(variant);
    }

    @Override
    @Transactional
    public ProductVariant updateVariantPrice(Long variantId, BigDecimal price) {
        ProductVariant variant = loadProductVariantPort.findVariantById(variantId)
                .orElseThrow(() -> new IllegalArgumentException("Variant not found: " + variantId));
        variant.updatePrice(price);
        return saveProductVariantPort.saveVariant(variant);
    }

    @Override
    @Transactional
    public ProductVariant updateVariantStock(Long variantId, int quantity) {
        ProductVariant variant = loadProductVariantPort.findVariantById(variantId)
                .orElseThrow(() -> new IllegalArgumentException("Variant not found: " + variantId));
        if (quantity >= 0) {
            variant.setStockQuantity(quantity);
        } else {
            throw new IllegalArgumentException("Stock quantity must be zero or greater");
        }
        return saveProductVariantPort.saveVariant(variant);
    }

    @Override
    public List<ProductVariant> getProductVariants(Long productId) {
        return loadProductVariantPort.findVariantsByProductId(productId);
    }

    @Override
    public ProductVariant getVariant(Long variantId) {
        return loadProductVariantPort.findVariantById(variantId)
                .orElseThrow(() -> new IllegalArgumentException("Variant not found: " + variantId));
    }

    @Override
    public ProductVariant getVariantBySku(String sku) {
        return loadProductVariantPort.findVariantBySku(sku)
                .orElseThrow(() -> new IllegalArgumentException("Variant not found with SKU: " + sku));
    }

    @Override
    @Transactional
    public void deactivateVariant(Long variantId) {
        ProductVariant variant = loadProductVariantPort.findVariantById(variantId)
                .orElseThrow(() -> new IllegalArgumentException("Variant not found: " + variantId));
        variant.deactivate();
        saveProductVariantPort.saveVariant(variant);
    }
}
