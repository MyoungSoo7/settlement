package github.lms.lemuel.product.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * ProductOption Domain Entity (순수 POJO)
 * 상품의 옵션 (예: 색상, 사이즈 등)
 */
public class ProductOption {

    private Long id;
    private Long productId;
    private String name;
    private int sortOrder;
    private List<ProductOptionValue> values;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 기본 생성자
    public ProductOption() {
        this.sortOrder = 0;
        this.values = new ArrayList<>();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // 전체 생성자
    public ProductOption(Long id, Long productId, String name, int sortOrder,
                         List<ProductOptionValue> values, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.productId = productId;
        this.name = name;
        this.sortOrder = sortOrder;
        this.values = values != null ? new ArrayList<>(values) : new ArrayList<>();
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.updatedAt = updatedAt != null ? updatedAt : LocalDateTime.now();
    }

    // 정적 팩토리 메서드
    public static ProductOption create(Long productId, String name, int sortOrder) {
        ProductOption option = new ProductOption();
        option.setProductId(productId);
        option.setName(name);
        option.setSortOrder(sortOrder);
        option.validateName();
        return option;
    }

    // 도메인 규칙: name 검증
    public void validateName() {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Option name cannot be empty");
        }
        if (name.length() > 50) {
            throw new IllegalArgumentException("Option name must not exceed 50 characters");
        }
    }

    // 비즈니스 메서드: 옵션 값 추가
    public void addValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Option value cannot be empty");
        }
        boolean exists = this.values.stream()
                .anyMatch(v -> v.getValue().equals(value));
        if (exists) {
            throw new IllegalArgumentException("Option value already exists: " + value);
        }
        int nextSortOrder = this.values.size();
        ProductOptionValue optionValue = ProductOptionValue.create(this.id, value, nextSortOrder);
        this.values.add(optionValue);
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 옵션 값 제거
    public void removeValue(String value) {
        this.values.removeIf(v -> v.getValue().equals(value));
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 이름 변경
    public void updateName(String name) {
        if (name != null && !name.trim().isEmpty()) {
            this.name = name;
            validateName();
            this.updatedAt = LocalDateTime.now();
        }
    }

    // 비즈니스 메서드: 정렬 순서 변경
    public void changeSortOrder(int order) {
        this.sortOrder = order;
        this.updatedAt = LocalDateTime.now();
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public List<ProductOptionValue> getValues() {
        return new ArrayList<>(values);
    }

    public void setValues(List<ProductOptionValue> values) {
        this.values = values != null ? new ArrayList<>(values) : new ArrayList<>();
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
