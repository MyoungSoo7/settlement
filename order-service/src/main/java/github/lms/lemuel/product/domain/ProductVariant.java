package github.lms.lemuel.product.domain;

import github.lms.lemuel.product.domain.exception.InsufficientStockException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 상품 옵션 (SKU / Variant) 도메인.
 *
 * <p>색상·사이즈 등 옵션 조합 1 개 = SKU 1 개. 옵션 상품의 재고는 {@link Product} 가 아닌
 * 이 객체에서 관리되며, 결제·주문은 productId 가 아닌 variantId(또는 sku) 를 기준으로 동작한다.
 *
 * <p>동시성 정책:
 * <ul>
 *   <li>{@code version} 필드를 JPA {@code @Version} 으로 매핑 → 동시 차감 시 OptimisticLockException</li>
 *   <li>충돌 시 애플리케이션 계층에서 N 회 재시도 ({@code DecreaseVariantStockService})</li>
 *   <li>대량 차감 (수천 RPS) 이 예상되면 Redis 분산 락 또는 DB Pessimistic Lock 으로 격상 가능</li>
 * </ul>
 */
public class ProductVariant {

    private Long id;
    private final Long productId;
    private final String sku;
    private String optionName;
    private BigDecimal additionalPrice;
    private int stockQuantity;
    private long version;
    private ProductVariantStatus status;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ProductVariant create(Long productId, String sku, String optionName,
                                         BigDecimal additionalPrice, int initialStock) {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(sku, "sku");
        if (sku.isBlank()) throw new IllegalArgumentException("sku 는 필수");
        if (optionName == null || optionName.isBlank()) {
            throw new IllegalArgumentException("optionName 은 필수 (예: '색상:빨강/사이즈:L')");
        }
        if (initialStock < 0) {
            throw new IllegalArgumentException("초기 재고는 0 이상");
        }
        BigDecimal price = additionalPrice == null ? BigDecimal.ZERO : additionalPrice;
        return new ProductVariant(null, productId, sku, optionName, price, initialStock,
                0L, initialStock == 0 ? ProductVariantStatus.OUT_OF_STOCK : ProductVariantStatus.ACTIVE,
                LocalDateTime.now(), LocalDateTime.now());
    }

    public static ProductVariant rehydrate(Long id, Long productId, String sku, String optionName,
                                            BigDecimal additionalPrice, int stockQuantity, long version,
                                            ProductVariantStatus status, LocalDateTime createdAt,
                                            LocalDateTime updatedAt) {
        return new ProductVariant(id, productId, sku, optionName, additionalPrice, stockQuantity,
                version, status, createdAt, updatedAt);
    }

    private ProductVariant(Long id, Long productId, String sku, String optionName,
                           BigDecimal additionalPrice, int stockQuantity, long version,
                           ProductVariantStatus status, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.productId = productId;
        this.sku = sku;
        this.optionName = optionName;
        this.additionalPrice = additionalPrice;
        this.stockQuantity = stockQuantity;
        this.version = version;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 재고 차감. 음수 재고는 도메인 불변식 위반이므로 즉시 예외.
     *
     * <p>동시성: 같은 트랜잭션에서 호출되더라도 JPA {@code @Version} 으로 다른 트랜잭션의 변경이
     * flush 시점에 감지된다. 충돌 발생 시 OptimisticLockException → 호출자가 재시도.
     */
    public void decreaseStock(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("차감 수량은 양수여야 합니다");
        }
        if (this.status == ProductVariantStatus.DISCONTINUED) {
            throw new IllegalStateException("단종된 SKU 는 차감할 수 없습니다: " + sku);
        }
        if (this.stockQuantity < quantity) {
            throw new InsufficientStockException(
                    "재고 부족: sku=" + sku + ", 요청=" + quantity + ", 가용=" + stockQuantity);
        }
        this.stockQuantity -= quantity;
        if (this.stockQuantity == 0) {
            this.status = ProductVariantStatus.OUT_OF_STOCK;
        }
        this.updatedAt = LocalDateTime.now();
    }

    public void increaseStock(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("증가 수량은 양수여야 합니다");
        }
        this.stockQuantity += quantity;
        if (this.status == ProductVariantStatus.OUT_OF_STOCK && stockQuantity > 0) {
            this.status = ProductVariantStatus.ACTIVE;
        }
        this.updatedAt = LocalDateTime.now();
    }

    public void discontinue() {
        this.status = ProductVariantStatus.DISCONTINUED;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isAvailable() {
        return status == ProductVariantStatus.ACTIVE && stockQuantity > 0;
    }

    public Long getId() { return id; }
    public Long getProductId() { return productId; }
    public String getSku() { return sku; }
    public String getOptionName() { return optionName; }
    public BigDecimal getAdditionalPrice() { return additionalPrice; }
    public int getStockQuantity() { return stockQuantity; }
    public long getVersion() { return version; }
    public ProductVariantStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    /**
     * Persistence 어댑터에서 INSERT 후 생성된 PK 를 주입할 때만 사용.
     */
    public void assignId(Long id) {
        if (this.id != null) {
            throw new IllegalStateException("id 는 1 회만 부여 가능");
        }
        this.id = id;
    }
}
