package github.lms.lemuel.cart.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * CartItem Domain Entity (순수 POJO, 스프링/JPA 의존성 없음)
 */
public class CartItem {

    private Long id;
    private Long cartId;
    private Long productId;
    private int quantity;
    private BigDecimal priceSnapshot;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 기본 생성자
    public CartItem() {
        this.quantity = 1;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // 전체 생성자
    public CartItem(Long id, Long cartId, Long productId, int quantity,
                    BigDecimal priceSnapshot, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.cartId = cartId;
        this.productId = productId;
        this.quantity = quantity;
        this.priceSnapshot = priceSnapshot;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.updatedAt = updatedAt != null ? updatedAt : LocalDateTime.now();
    }

    // 비즈니스 메서드: 수량 변경
    public void updateQuantity(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        this.quantity = quantity;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 수량 증가
    public void increaseQuantity(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Increase amount must be positive");
        }
        this.quantity += amount;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 수량 감소
    public void decreaseQuantity(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Decrease amount must be positive");
        }
        if (this.quantity - amount <= 0) {
            throw new IllegalStateException("Quantity cannot be zero or negative after decrease");
        }
        this.quantity -= amount;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 소계 계산
    public BigDecimal getSubtotal() {
        if (priceSnapshot == null) {
            return BigDecimal.ZERO;
        }
        return priceSnapshot.multiply(BigDecimal.valueOf(quantity));
    }

    // 도메인 검증
    public void validate() {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        if (priceSnapshot == null || priceSnapshot.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Price snapshot must be positive");
        }
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCartId() {
        return cartId;
    }

    public void setCartId(Long cartId) {
        this.cartId = cartId;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getPriceSnapshot() {
        return priceSnapshot;
    }

    public void setPriceSnapshot(BigDecimal priceSnapshot) {
        this.priceSnapshot = priceSnapshot;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
