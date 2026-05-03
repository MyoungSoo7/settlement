package github.lms.lemuel.product.adapter.in.web.response;

import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ProductResponse(
        Long id,
        String name,
        String description,
        BigDecimal price,
        Integer stockQuantity,
        ProductStatus status,
        boolean availableForSale,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String primaryImageUrl
) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getStockQuantity(),
                product.getStatus(),
                product.isAvailableForSale(),
                product.getCreatedAt(),
                product.getUpdatedAt(),
                null
        );
    }

    public static ProductResponse from(Product product, String primaryImageUrl) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getStockQuantity(),
                product.getStatus(),
                product.isAvailableForSale(),
                product.getCreatedAt(),
                product.getUpdatedAt(),
                primaryImageUrl
        );
    }
}
