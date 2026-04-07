package github.lms.lemuel.product.domain;

import java.time.LocalDateTime;

/**
 * ProductOptionValue Domain Entity (순수 POJO)
 * 옵션의 구체적인 값 (예: Red, Blue, XL, M 등)
 */
public class ProductOptionValue {

    private Long id;
    private Long optionId;
    private String value;
    private int sortOrder;
    private LocalDateTime createdAt;

    // 기본 생성자
    public ProductOptionValue() {
        this.sortOrder = 0;
        this.createdAt = LocalDateTime.now();
    }

    // 전체 생성자
    public ProductOptionValue(Long id, Long optionId, String value, int sortOrder, LocalDateTime createdAt) {
        this.id = id;
        this.optionId = optionId;
        this.value = value;
        this.sortOrder = sortOrder;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
    }

    // 정적 팩토리 메서드
    public static ProductOptionValue create(Long optionId, String value, int sortOrder) {
        ProductOptionValue optionValue = new ProductOptionValue();
        optionValue.setOptionId(optionId);
        optionValue.setValue(value);
        optionValue.setSortOrder(sortOrder);
        optionValue.validateValue();
        return optionValue;
    }

    // 도메인 규칙: value 검증
    public void validateValue() {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Option value cannot be empty");
        }
        if (value.length() > 100) {
            throw new IllegalArgumentException("Option value must not exceed 100 characters");
        }
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOptionId() {
        return optionId;
    }

    public void setOptionId(Long optionId) {
        this.optionId = optionId;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
