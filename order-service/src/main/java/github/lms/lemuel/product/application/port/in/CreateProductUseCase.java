package github.lms.lemuel.product.application.port.in;

import github.lms.lemuel.product.domain.Product;

import java.math.BigDecimal;

public interface CreateProductUseCase {

    Product createProduct(CreateProductCommand command);

    record CreateProductCommand(
            String name,
            String description,
            BigDecimal price,
            Integer stockQuantity
    ) {
        public CreateProductCommand {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("Product name cannot be empty");
            }
            if (price == null || price.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Price must be zero or greater");
            }
            if (stockQuantity == null || stockQuantity < 0) {
                throw new IllegalArgumentException("Stock quantity must be zero or greater");
            }
        }
    }
}
