package github.lms.lemuel.product.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ProductVariant Domain Entity (순수 POJO)
 * 상품의 변형 (옵션 조합별 SKU, 가격, 재고)
 */
public class ProductVariant {

    private Long id;
    private Long productId;
    private String sku;
    private BigDecimal price;
    private int stockQuantity;
    private String optionValues; // 쉼표 구분 (예: "Red,XL")
    private boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 기본 생성자
    public ProductVariant() {
        this.stockQuantity = 0;
        this.isActive = true;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // 전체 생성자
    public ProductVariant(Long id, Long productId, String sku, BigDecimal price,
                          int stockQuantity, String optionValues, boolean isActive,
                          LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.productId = productId;
        this.sku = sku;
        this.price = price;
        this.stockQuantity = stockQuantity;
        this.optionValues = optionValues;
        this.isActive = isActive;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.updatedAt = updatedAt != null ? updatedAt : LocalDateTime.now();
    }

    // 정적 팩토리 메서드
    public static ProductVariant create(Long productId, String sku, BigDecimal price,
                                        int stockQuantity, String optionValues) {
        ProductVariant variant = new ProductVariant();
        variant.setProductId(productId);
        variant.setSku(sku);
        variant.setPrice(price);
        variant.setStockQuantity(stockQuantity);
        variant.setOptionValues(optionValues);
        variant.validateSku();
        variant.validatePrice();
        variant.validateStockQuantity();
        return variant;
    }

    // 도메인 규칙: SKU 검증
    public void validateSku() {
        if (sku == null || sku.trim().isEmpty()) {
            throw new IllegalArgumentException("SKU cannot be empty");
        }
        if (sku.length() > 100) {
            throw new IllegalArgumentException("SKU must not exceed 100 characters");
        }
    }

    // 도메인 규칙: price 검증
    public void validatePrice() {
        if (price == null || price.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Variant price must be zero or greater");
        }
    }

    // 도메인 규칙: stockQuantity 검증
    public void validateStockQuantity() {
        if (stockQuantity < 0) {
            throw new IllegalArgumentException("Stock quantity must be zero or greater");
        }
    }

    // 비즈니스 메서드: 활성화
    public void activate() {
        this.isActive = true;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 비활성화
    public void deactivate() {
        this.isActive = false;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 가격 변경
    public void updatePrice(BigDecimal newPrice) {
        if (newPrice == null || newPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("New price must be zero or greater");
        }
        this.price = newPrice;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 재고 증가
    public void increaseStock(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Increase quantity must be positive");
        }
        this.stockQuantity += quantity;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 재고 감소
    public void decreaseStock(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Decrease quantity must be positive");
        }
        if (this.stockQuantity < quantity) {
            throw new IllegalStateException("Insufficient stock: requested=" + quantity + ", available=" + this.stockQuantity);
        }
        this.stockQuantity -= quantity;
        this.updatedAt = LocalDateTime.now();
    }

    // 상태 확인 메서드: 재고 있음
    public boolean hasStock() {
        return this.stockQuantity > 0;
    }

    // 상태 확인 메서드: 판매 가능
    public boolean isAvailable() {
        return this.isActive && hasStock();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public int getStockQuantity() {
        return stockQuantity;
    }

    public void setStockQuantity(int stockQuantity) {
        this.stockQuantity = stockQuantity;
    }

    public String getOptionValues() {
        return optionValues;
    }

    public void setOptionValues(String optionValues) {
        this.optionValues = optionValues;
    }

    public boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(boolean isActive) {
        this.isActive = isActive;
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
