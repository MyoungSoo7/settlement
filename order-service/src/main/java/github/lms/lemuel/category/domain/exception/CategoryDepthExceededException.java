package github.lms.lemuel.category.domain.exception;

public class CategoryDepthExceededException extends RuntimeException {
    public CategoryDepthExceededException(int attemptedDepth, int maxDepth) {
        super(String.format("Category depth %d exceeds maximum allowed depth of %d", attemptedDepth, maxDepth));
    }

    public CategoryDepthExceededException(String message) {
        super(message);
    }
}
