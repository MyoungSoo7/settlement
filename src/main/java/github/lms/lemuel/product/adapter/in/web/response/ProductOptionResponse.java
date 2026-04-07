package github.lms.lemuel.product.adapter.in.web.response;

import github.lms.lemuel.product.domain.ProductOption;
import github.lms.lemuel.product.domain.ProductOptionValue;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.stream.Collectors;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ProductOptionResponse {

    private Long id;
    private Long productId;
    private String name;
    private int sortOrder;
    private List<OptionValueResponse> values;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptionValueResponse {
        private Long id;
        private String value;
        private int sortOrder;

        public static OptionValueResponse from(ProductOptionValue optionValue) {
            return new OptionValueResponse(
                    optionValue.getId(),
                    optionValue.getValue(),
                    optionValue.getSortOrder()
            );
        }
    }

    public static ProductOptionResponse from(ProductOption option) {
        List<OptionValueResponse> valueResponses = option.getValues().stream()
                .map(OptionValueResponse::from)
                .collect(Collectors.toList());

        return new ProductOptionResponse(
                option.getId(),
                option.getProductId(),
                option.getName(),
                option.getSortOrder(),
                valueResponses
        );
    }
}
