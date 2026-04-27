package github.lms.lemuel.category.domain.exception;

public class CategoryNotFoundException extends RuntimeException {
    public CategoryNotFoundException(Long id) {
        super("Category not found: " + id);
    }

    public CategoryNotFoundException(String slug) {
        super("Category not found with slug: " + slug);
    }
}
