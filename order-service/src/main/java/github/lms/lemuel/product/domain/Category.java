package github.lms.lemuel.product.domain;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;

/**
 * Category Domain Entity (순수 POJO)
 * 계층형 카테고리 구조 지원
 */
@Getter
public class Category {

    private Long id;
    private String name;
    private String description;
    private Long parentId;
    private Integer displayOrder;
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 기본 생성자
    public Category() {
        this.displayOrder = 0;
        this.isActive = true;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // 전체 생성자
    public Category(Long id, String name, String description, Long parentId,
                    Integer displayOrder, Boolean isActive,
                    LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.parentId = parentId;
        this.displayOrder = displayOrder != null ? displayOrder : 0;
        this.isActive = isActive != null ? isActive : true;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.updatedAt = updatedAt != null ? updatedAt : LocalDateTime.now();
    }

    // 정적 팩토리 메서드: 최상위 카테고리 생성
    public static Category create(String name, String description, Integer displayOrder) {
        Category category = new Category();
        category.name = name;
        category.description = description;
        category.displayOrder = displayOrder;
        category.validateName();
        return category;
    }

    // 정적 팩토리 메서드: 하위 카테고리 생성
    public static Category createSubCategory(String name, String description, Long parentId, Integer displayOrder) {
        Category category = new Category();
        category.name = name;
        category.description = description;
        category.parentId = parentId;
        category.displayOrder = displayOrder;
        category.validateName();
        return category;
    }

    /** 영속 저장이 부여한 식별자를 1회 부여한다(식별자는 불변 — 이미 부여됐으면 거부). */
    public void assignId(Long id) {
        if (this.id != null && !this.id.equals(id)) {
            throw new ProductInvariantViolationException("Category id is already assigned and immutable");
        }
        this.id = id;
    }

    // 도메인 규칙: name 검증
    public void validateName() {
        if (name == null || name.trim().isEmpty()) {
            throw new ProductInvariantViolationException("Category name cannot be empty");
        }
        if (name.length() > 100) {
            throw new ProductInvariantViolationException("Category name must not exceed 100 characters");
        }
    }

    // 비즈니스 메서드: 카테고리 활성화
    public void activate() {
        this.isActive = true;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 카테고리 비활성화
    public void deactivate() {
        this.isActive = false;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 카테고리 정보 업데이트
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

    // 비즈니스 메서드: 표시 순서 변경
    public void changeDisplayOrder(Integer newDisplayOrder) {
        if (newDisplayOrder == null || newDisplayOrder < 0) {
            throw new ProductInvariantViolationException("Display order must be zero or greater");
        }
        this.displayOrder = newDisplayOrder;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 부모 카테고리 변경
    public void changeParent(Long newParentId) {
        if (newParentId != null && newParentId.equals(this.id)) {
            throw new ProductInvariantViolationException("Category cannot be its own parent");
        }
        this.parentId = newParentId;
        this.updatedAt = LocalDateTime.now();
    }

    // 상태 확인 메서드
    public boolean isRootCategory() {
        return this.parentId == null;
    }

    public boolean isSubCategory() {
        return this.parentId != null;
    }

}
