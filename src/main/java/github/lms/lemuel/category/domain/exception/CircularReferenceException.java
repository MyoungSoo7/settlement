package github.lms.lemuel.category.domain.exception;

public class CircularReferenceException extends RuntimeException {
    public CircularReferenceException(Long categoryId, Long parentId) {
        super(String.format("Circular reference detected: category %d cannot have parent %d (would create a cycle)", categoryId, parentId));
    }

    public CircularReferenceException(String message) {
        super(message);
    }
}
