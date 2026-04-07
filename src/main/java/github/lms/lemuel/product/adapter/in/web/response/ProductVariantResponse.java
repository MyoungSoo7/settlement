package github.lms.lemuel.product.adapter.in.web.response;

import github.lms.lemuel.product.domain.ProductVariant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ProductVariantResponse {

    private Long id;
    private Long productId;
    private String sku;
    private BigDecimal price;
    private int stockQuantity;
    private String optionValues;
    private boolean isActive;

    public static ProductVariantResponse from(ProductVariant variant) {
        return new ProductVariantResponse(
                variant.getId(),
                variant.getProductId(),
                variant.getSku(),
                variant.getPrice(),
                variant.getStockQuantity(),
                variant.getOptionValues(),
                variant.getIsActive()
        );
    }
}
