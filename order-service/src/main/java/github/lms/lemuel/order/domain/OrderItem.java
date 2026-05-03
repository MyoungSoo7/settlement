package github.lms.lemuel.order.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 주문 라인 아이템 — Order 의 자식 도메인 객체.
 *
 * <p>특징:
 * <ul>
 *   <li>{@code productName}, {@code unitPrice} 는 <b>주문 시점 스냅샷</b>으로 영구 보관 —
 *       추후 상품 가격이 바뀌어도 영수증·정산 금액에는 영향 없음 (이력 보존)</li>
 *   <li>{@code variantId} 는 옵션 상품(SKU) 주문일 때만 채워진다. 옵션 없는 단일 상품은 null</li>
 *   <li>{@code lineAmount} = {@code unitPrice * quantity}. 도메인이 직접 계산해
 *       JPA Generated Column 의존을 없앤다 → 단위 테스트 용이</li>
 * </ul>
 */
public class OrderItem {

    private Long id;
    private Long orderId;
    private final Long productId;
    private final Long variantId;       // SKU 주문이면 채움, 아니면 null
    private final String sku;            // SKU 문자열 스냅샷 (감사용)
    private final String productName;    // 주문 시점 상품명
    private final BigDecimal unitPrice;  // 주문 시점 단가 (할인 적용 후)
    private final int quantity;
    private final BigDecimal lineAmount; // unitPrice * quantity
    private final LocalDateTime createdAt;

    public static OrderItem newItem(Long productId, Long variantId, String sku,
                                     String productName, BigDecimal unitPrice, int quantity) {
        Objects.requireNonNull(productId, "productId");
        if (productName == null || productName.isBlank()) {
            throw new IllegalArgumentException("productName 은 필수");
        }
        if (unitPrice == null || unitPrice.signum() < 0) {
            throw new IllegalArgumentException("unitPrice 는 0 이상");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity 는 양수");
        }
        BigDecimal line = unitPrice.multiply(BigDecimal.valueOf(quantity));
        return new OrderItem(null, null, productId, variantId, sku, productName,
                unitPrice, quantity, line, LocalDateTime.now());
    }

    public static OrderItem rehydrate(Long id, Long orderId, Long productId, Long variantId,
                                       String sku, String productName, BigDecimal unitPrice,
                                       int quantity, BigDecimal lineAmount, LocalDateTime createdAt) {
        return new OrderItem(id, orderId, productId, variantId, sku, productName,
                unitPrice, quantity, lineAmount, createdAt);
    }

    private OrderItem(Long id, Long orderId, Long productId, Long variantId, String sku,
                      String productName, BigDecimal unitPrice, int quantity,
                      BigDecimal lineAmount, LocalDateTime createdAt) {
        this.id = id;
        this.orderId = orderId;
        this.productId = productId;
        this.variantId = variantId;
        this.sku = sku;
        this.productName = productName;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.lineAmount = lineAmount;
        this.createdAt = createdAt;
    }

    void attachToOrder(Long orderId) {
        if (this.orderId != null && !this.orderId.equals(orderId)) {
            throw new IllegalStateException("이미 다른 주문에 속한 아이템: " + this.orderId);
        }
        this.orderId = orderId;
    }

    public void assignId(Long id) {
        if (this.id != null) {
            throw new IllegalStateException("id 는 1회만 부여 가능");
        }
        this.id = id;
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public Long getProductId() { return productId; }
    public Long getVariantId() { return variantId; }
    public String getSku() { return sku; }
    public String getProductName() { return productName; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public int getQuantity() { return quantity; }
    public BigDecimal getLineAmount() { return lineAmount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
