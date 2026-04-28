package github.lms.lemuel.product.domain.exception;

public class InsufficientStockException extends RuntimeException {

    private final Long productId;
    private final int requestedQuantity;
    private final int availableQuantity;

    public InsufficientStockException(Long productId, int requestedQuantity, int availableQuantity) {
        super(String.format("Insufficient stock for product %d: requested=%d, available=%d",
                productId, requestedQuantity, availableQuantity));
        this.productId = productId;
        this.requestedQuantity = requestedQuantity;
        this.availableQuantity = availableQuantity;
    }

    /**
     * SKU(Variant) 재고 부족 — productId 가 sku 문자열을 통해 간접 식별되는 경우 사용.
     */
    public InsufficientStockException(String message) {
        super(message);
        this.productId = null;
        this.requestedQuantity = 0;
        this.availableQuantity = 0;
    }

    public Long getProductId() {
        return productId;
    }

    public int getRequestedQuantity() {
        return requestedQuantity;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }
}
