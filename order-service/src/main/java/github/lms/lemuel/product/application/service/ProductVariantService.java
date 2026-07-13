package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.CreateProductVariantUseCase;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.application.port.out.SaveProductVariantPort;
import github.lms.lemuel.product.domain.ProductVariant;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@Transactional
public class ProductVariantService implements CreateProductVariantUseCase {

    private final LoadProductPort loadProductPort;
    private final LoadProductVariantPort loadVariantPort;
    private final SaveProductVariantPort saveVariantPort;

    public ProductVariantService(LoadProductPort loadProductPort,
                                  LoadProductVariantPort loadVariantPort,
                                  SaveProductVariantPort saveVariantPort) {
        this.loadProductPort = loadProductPort;
        this.loadVariantPort = loadVariantPort;
        this.saveVariantPort = saveVariantPort;
    }

    @Override
    public ProductVariant create(Long productId, String sku, String optionName,
                                  BigDecimal additionalPrice, int initialStock) {
        // 1) 상품 존재 검증
        loadProductPort.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        // 2) SKU 중복 검증
        if (loadVariantPort.loadBySku(sku).isPresent()) {
            throw new ProductInvariantViolationException("이미 사용 중인 SKU 입니다: " + sku);
        }

        ProductVariant variant = ProductVariant.create(productId, sku, optionName,
                additionalPrice, initialStock);
        return saveVariantPort.save(variant);
    }

    @Transactional(readOnly = true)
    public List<ProductVariant> listByProductId(Long productId) {
        return loadVariantPort.loadByProductId(productId);
    }
}
