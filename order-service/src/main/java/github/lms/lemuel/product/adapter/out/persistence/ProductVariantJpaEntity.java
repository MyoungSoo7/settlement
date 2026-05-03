package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.domain.ProductVariantStatus;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "product_variants")
public class ProductVariantJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false, length = 64, unique = true)
    private String sku;

    @Column(name = "option_name", nullable = false, length = 200)
    private String optionName;

    @Column(name = "additional_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal additionalPrice;

    @Column(name = "stock_quantity", nullable = false)
    private int stockQuantity;

    /**
     * Optimistic Lock — 동시 재고 차감 시 충돌 감지.
     */
    @Version
    @Column(nullable = false)
    private long version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductVariantStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ProductVariantJpaEntity() { }

    public ProductVariantJpaEntity(Long id, Long productId, String sku, String optionName,
                                    BigDecimal additionalPrice, int stockQuantity, long version,
                                    ProductVariantStatus status, LocalDateTime createdAt,
                                    LocalDateTime updatedAt) {
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

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
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

    public void applyDomainState(int stockQuantity, ProductVariantStatus status,
                                  String optionName, BigDecimal additionalPrice,
                                  LocalDateTime updatedAt) {
        this.stockQuantity = stockQuantity;
        this.status = status;
        this.optionName = optionName;
        this.additionalPrice = additionalPrice;
        this.updatedAt = updatedAt;
    }
}
