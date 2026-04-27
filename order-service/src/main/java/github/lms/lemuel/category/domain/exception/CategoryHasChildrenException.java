package github.lms.lemuel.category.domain.exception;

public class CategoryHasChildrenException extends RuntimeException {
    public CategoryHasChildrenException(Long categoryId, long childCount) {
        super(String.format("Cannot delete category %d: has %d child categories", categoryId, childCount));
    }
}
