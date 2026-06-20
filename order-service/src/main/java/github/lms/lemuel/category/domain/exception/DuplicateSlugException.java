package github.lms.lemuel.category.domain.exception;

public class DuplicateSlugException extends RuntimeException {
    public DuplicateSlugException(String slug) {
        super("Category slug already exists: " + slug);
    }
}
