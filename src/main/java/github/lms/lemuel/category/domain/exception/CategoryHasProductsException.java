package github.lms.lemuel.category.domain.exception;

public class CategoryHasProductsException extends RuntimeException {
    public CategoryHasProductsException(Long categoryId) {
        super(String.format("Cannot delete category %d: has associated products", categoryId));
    }
}
