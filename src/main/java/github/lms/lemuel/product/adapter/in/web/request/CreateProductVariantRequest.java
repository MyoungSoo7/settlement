package github.lms.lemuel.product.adapter.in.web.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CreateProductVariantRequest {
    private String sku;
    private BigDecimal price;
    private Integer stockQuantity;
    private String optionValues;
}
