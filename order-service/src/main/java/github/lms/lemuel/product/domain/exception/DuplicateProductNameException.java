package github.lms.lemuel.product.domain.exception;

public class DuplicateProductNameException extends RuntimeException {

    public DuplicateProductNameException(String productName) {
        super("Product already exists with name: " + productName);
    }
}
