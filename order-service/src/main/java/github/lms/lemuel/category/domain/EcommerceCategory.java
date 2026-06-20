package github.lms.lemuel.category.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * EcommerceCategory Domain Entity
 * 트리 구조 카테고리 (최대 3단계: 0, 1, 2)
 */
public class EcommerceCategory {

    public static final int MAX_DEPTH = 2;

    private Long id;
    private String name;
    private String slug;
    private Long parentId;
    private Integer depth;
    private Integer sortOrder;
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
    private List<EcommerceCategory> children;

    public EcommerceCategory() {
        this.depth = 0;
        this.sortOrder = 0;
        this.isActive = true;
        this.children = new ArrayList<>();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public EcommerceCategory(Long id, String name, String slug, Long parentId,
                             Integer depth, Integer sortOrder, Boolean isActive,
                             LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime deletedAt) {
        this.id = id;
        this.name = name;
        this.slug = slug;
        this.parentId = parentId;
        this.depth = depth != null ? depth : 0;
        this.sortOrder = sortOrder != null ? sortOrder : 0;
        this.isActive = isActive != null ? isActive : true;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.updatedAt = updatedAt != null ? updatedAt : LocalDateTime.now();
        this.deletedAt = deletedAt;
        this.children = new ArrayList<>();
    }

    // 정적 팩토리 메서드: 최상위 카테고리 생성
    public static EcommerceCategory createRoot(String name, String slug, Integer sortOrder) {
        EcommerceCategory category = new EcommerceCategory();
        category.setName(name);
        category.setSlug(slug);
        category.setDepth(0);
        category.setSortOrder(sortOrder);
        category.validateName();
        category.validateSlug();
        return category;
    }

    // 정적 팩토리 메서드: 하위 카테고리 생성
    public static EcommerceCategory createChild(String name, String slug, Long parentId, Integer parentDepth, Integer sortOrder) {
        if (parentDepth == null) {
            throw new IllegalArgumentException("Parent depth must not be null");
        }

        int newDepth = parentDepth + 1;
        if (newDepth > MAX_DEPTH) {
            throw new IllegalStateException("Category depth cannot exceed " + MAX_DEPTH + " (attempted: " + newDepth + ")");
        }

        EcommerceCategory category = new EcommerceCategory();
        category.setName(name);
        category.setSlug(slug);
        category.setParentId(parentId);
        category.setDepth(newDepth);
        category.setSortOrder(sortOrder);
        category.validateName();
        category.validateSlug();
        category.validateParentId();
        return category;
    }

    // 도메인 규칙: name 검증
    public void validateName() {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Category name cannot be empty");
        }
        if (name.length() > 200) {
            throw new IllegalArgumentException("Category name must not exceed 200 characters");
        }
    }

    // 도메인 규칙: slug 검증
    public void validateSlug() {
        if (slug == null || slug.trim().isEmpty()) {
            throw new IllegalArgumentException("Category slug cannot be empty");
        }
        if (slug.length() > 300) {
            throw new IllegalArgumentException("Category slug must not exceed 300 characters");
        }
        if (!slug.matches("^[a-z0-9-]+$")) {
            throw new IllegalArgumentException("Category slug must contain only lowercase letters, numbers, and hyphens");
        }
    }

    // 도메인 규칙: parentId 검증 (순환 참조 방지)
    public void validateParentId() {
        if (parentId != null && parentId.equals(id)) {
            throw new IllegalArgumentException("Category cannot be its own parent (circular reference)");
        }
    }

    // 도메인 규칙: depth 검증
    public void validateDepth() {
        if (depth == null || depth < 0 || depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Category depth must be between 0 and " + MAX_DEPTH);
        }
    }

    // 비즈니스 메서드: 부모 변경
    public void changeParent(Long newParentId, Integer newParentDepth) {
        if (newParentId != null && newParentId.equals(this.id)) {
            throw new IllegalArgumentException("Category cannot be its own parent");
        }

        if (newParentId == null) {
            this.parentId = null;
            this.depth = 0;
        } else {
            int newDepth = newParentDepth + 1;
            if (newDepth > MAX_DEPTH) {
                throw new IllegalStateException("Moving category would exceed maximum depth of " + MAX_DEPTH);
            }
            this.parentId = newParentId;
            this.depth = newDepth;
        }
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 정렬 순서 변경
    public void changeSortOrder(Integer newSortOrder) {
        if (newSortOrder == null || newSortOrder < 0) {
            throw new IllegalArgumentException("Sort order must be zero or greater");
        }
        this.sortOrder = newSortOrder;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 활성화
    public void activate() {
        if (isDeleted()) {
            throw new IllegalStateException("Cannot activate deleted category");
        }
        this.isActive = true;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 비활성화
    public void deactivate() {
        this.isActive = false;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: soft delete
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
        this.isActive = false;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 정보 업데이트
    public void updateInfo(String name, String slug) {
        if (name != null && !name.trim().isEmpty()) {
            this.name = name;
            validateName();
        }
        if (slug != null && !slug.trim().isEmpty()) {
            this.slug = slug;
            validateSlug();
        }
        this.updatedAt = LocalDateTime.now();
    }

    // 상태 확인 메서드
    public boolean isRoot() {
        return this.parentId == null && this.depth == 0;
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
    }

    public boolean canHaveChildren() {
        return this.depth < MAX_DEPTH;
    }

    public void addChild(EcommerceCategory child) {
        this.children.add(child);
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

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public Integer getDepth() {
        return depth;
    }

    public void setDepth(Integer depth) {
        this.depth = depth;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
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

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public List<EcommerceCategory> getChildren() {
        return new ArrayList<>(children);
    }

    public void setChildren(List<EcommerceCategory> children) {
        this.children = children != null ? new ArrayList<>(children) : new ArrayList<>();
    }
}
