package github.lms.lemuel.product.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Product Domain Entity (순수 POJO, 스프링/JPA 의존성 없음)
 * DB 스키마: id, name, description, price, stock_quantity, status, category_id, created_at, updated_at
 */
public class Product {

    private Long id;
    private String name;
    private String description;
    private BigDecimal price;
    private Integer stockQuantity;
    private ProductStatus status;
    private Long categoryId;
    private List<Long> tagIds;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 기본 생성자
    public Product() {
        this.status = ProductStatus.ACTIVE;
        this.stockQuantity = 0;
        this.tagIds = new ArrayList<>();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // 전체 생성자
    public Product(Long id, String name, String description, BigDecimal price,
                   Integer stockQuantity, ProductStatus status, Long categoryId, List<Long> tagIds,
                   LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.stockQuantity = stockQuantity != null ? stockQuantity : 0;
        this.status = status != null ? status : ProductStatus.ACTIVE;
        this.categoryId = categoryId;
        this.tagIds = tagIds != null ? new ArrayList<>(tagIds) : new ArrayList<>();
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.updatedAt = updatedAt != null ? updatedAt : LocalDateTime.now();
    }

    // 정적 팩토리 메서드
    public static Product create(String name, String description, BigDecimal price, Integer stockQuantity) {
        Product product = new Product();
        product.setName(name);
        product.setDescription(description);
        product.setPrice(price);
        product.setStockQuantity(stockQuantity);
        product.validateName();
        product.validatePrice();
        product.validateStockQuantity();
        return product;
    }

    // 도메인 규칙: name 검증
    public void validateName() {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Product name cannot be empty");
        }
        if (name.length() > 200) {
            throw new IllegalArgumentException("Product name must not exceed 200 characters");
        }
    }

    // 도메인 규칙: price 검증
    public void validatePrice() {
        if (price == null || price.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Product price must be zero or greater");
        }
    }

    // 도메인 규칙: stockQuantity 검증
    public void validateStockQuantity() {
        if (stockQuantity == null || stockQuantity < 0) {
            throw new IllegalArgumentException("Stock quantity must be zero or greater");
        }
    }

    // 비즈니스 메서드: 재고 증가
    public void increaseStock(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Increase quantity must be positive");
        }
        this.stockQuantity += quantity;
        this.updatedAt = LocalDateTime.now();

        // 재고가 다시 생겼을 때 품절 상태 해제
        if (this.status == ProductStatus.OUT_OF_STOCK && this.stockQuantity > 0) {
            this.status = ProductStatus.ACTIVE;
        }
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

        // 재고가 0이 되면 품절 상태로 변경
        if (this.stockQuantity == 0 && this.status == ProductStatus.ACTIVE) {
            this.status = ProductStatus.OUT_OF_STOCK;
        }
    }

    // 비즈니스 메서드: 가격 변경
    public void changePrice(BigDecimal newPrice) {
        if (newPrice == null || newPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("New price must be zero or greater");
        }
        this.price = newPrice;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 상품 활성화
    public void activate() {
        if (this.status == ProductStatus.DISCONTINUED) {
            throw new IllegalStateException("Cannot activate discontinued product");
        }
        if (this.stockQuantity == 0) {
            this.status = ProductStatus.OUT_OF_STOCK;
        } else {
            this.status = ProductStatus.ACTIVE;
        }
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 상품 비활성화
    public void deactivate() {
        if (this.status == ProductStatus.DISCONTINUED) {
            throw new IllegalStateException("Cannot deactivate discontinued product");
        }
        this.status = ProductStatus.INACTIVE;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 상품 단종
    public void discontinue() {
        this.status = ProductStatus.DISCONTINUED;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 상품 정보 업데이트
    public void updateInfo(String name, String description) {
        if (name != null && !name.trim().isEmpty()) {
            this.name = name;
            validateName();
        }
        if (description != null) {
            this.description = description;
        }
        this.updatedAt = LocalDateTime.now();
    }

    // 상태 확인 메서드
    public boolean isAvailableForSale() {
        return this.status == ProductStatus.ACTIVE && this.stockQuantity > 0;
    }

    public boolean hasStock() {
        return this.stockQuantity != null && this.stockQuantity > 0;
    }

    public boolean isActive() {
        return this.status == ProductStatus.ACTIVE;
    }

    public boolean isDiscontinued() {
        return this.status == ProductStatus.DISCONTINUED;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getStockQuantity() {
        return stockQuantity;
    }

    public void setStockQuantity(Integer stockQuantity) {
        this.stockQuantity = stockQuantity;
    }

    public ProductStatus getStatus() {
        return status;
    }

    public void setStatus(ProductStatus status) {
        this.status = status;
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

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
        this.updatedAt = LocalDateTime.now();
    }

    public List<Long> getTagIds() {
        return new ArrayList<>(tagIds);
    }

    public void setTagIds(List<Long> tagIds) {
        this.tagIds = tagIds != null ? new ArrayList<>(tagIds) : new ArrayList<>();
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 태그 추가
    public void addTag(Long tagId) {
        if (tagId == null) {
            throw new IllegalArgumentException("Tag ID cannot be null");
        }
        if (!this.tagIds.contains(tagId)) {
            this.tagIds.add(tagId);
            this.updatedAt = LocalDateTime.now();
        }
    }

    // 비즈니스 메서드: 태그 제거
    public void removeTag(Long tagId) {
        this.tagIds.remove(tagId);
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 모든 태그 제거
    public void clearTags() {
        this.tagIds.clear();
        this.updatedAt = LocalDateTime.now();
    }

    // 상태 확인 메서드: 특정 태그 보유 여부
    public boolean hasTag(Long tagId) {
        return this.tagIds.contains(tagId);
    }
}
