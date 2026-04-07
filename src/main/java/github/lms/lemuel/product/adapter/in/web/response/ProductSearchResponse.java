package github.lms.lemuel.product.adapter.in.web.response;

import github.lms.lemuel.product.domain.Product;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

public record ProductSearchResponse(
        Long id,
        String name,
        String description,
        BigDecimal price,
        Integer stockQuantity,
        String status,
        String categoryName,
        List<String> tags
) {
    public static ProductSearchResponse from(Product product) {
        return new ProductSearchResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getStockQuantity(),
                product.getStatus() != null ? product.getStatus().name() : null,
                null,
                null
        );
    }

    public record PageResponse(
            List<ProductSearchResponse> content,
            long totalElements,
            int totalPages,
            int page,
            int size
    ) {
        public static PageResponse from(Page<Product> productPage) {
            List<ProductSearchResponse> content = productPage.getContent().stream()
                    .map(ProductSearchResponse::from)
                    .collect(Collectors.toList());
            return new PageResponse(
                    content,
                    productPage.getTotalElements(),
                    productPage.getTotalPages(),
                    productPage.getNumber(),
                    productPage.getSize()
            );
        }
    }
}
