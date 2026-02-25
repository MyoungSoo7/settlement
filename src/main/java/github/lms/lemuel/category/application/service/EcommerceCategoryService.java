package github.lms.lemuel.category.application.service;

import github.lms.lemuel.category.adapter.out.persistence.EcommerceCategoryJpaEntity;
import github.lms.lemuel.category.adapter.out.persistence.EcommerceCategoryMapper;
import github.lms.lemuel.category.adapter.out.persistence.SpringDataEcommerceCategoryRepository;
import github.lms.lemuel.category.domain.EcommerceCategory;
import github.lms.lemuel.category.domain.exception.*;
import github.lms.lemuel.category.util.SlugGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EcommerceCategoryService {

    private final SpringDataEcommerceCategoryRepository repository;
    private final EcommerceCategoryMapper mapper;
    private final SlugGenerator slugGenerator;

    /**
     * 카테고리 생성
     */
    @Transactional
    public EcommerceCategory createCategory(String name, String slug, Long parentId, Integer sortOrder) {
        // Slug 자동 생성
        if (slug == null || slug.trim().isEmpty()) {
            if (parentId != null) {
                EcommerceCategory parent = getCategoryById(parentId);
                slug = slugGenerator.generateWithParent(parent.getSlug(), name);
            } else {
                slug = slugGenerator.generate(name);
            }
        }

        // Slug 중복 체크
        if (repository.findBySlug(slug).isPresent()) {
            throw new DuplicateSlugException(slug);
        }

        EcommerceCategory category;
        if (parentId == null) {
            // 최상위 카테고리
            category = EcommerceCategory.createRoot(name, slug, sortOrder != null ? sortOrder : 0);
        } else {
            // 하위 카테고리
            EcommerceCategory parent = getCategoryById(parentId);
            if (parent.isDeleted()) {
                throw new IllegalArgumentException("Cannot create category under deleted parent");
            }
            category = EcommerceCategory.createChild(name, slug, parentId, parent.getDepth(), sortOrder != null ? sortOrder : 0);
        }

        EcommerceCategoryJpaEntity saved = repository.save(mapper.toJpaEntity(category));
        return mapper.toDomainEntity(saved);
    }

    /**
     * 카테고리 조회 (ID)
     */
    public EcommerceCategory getCategoryById(Long id) {
        return repository.findByIdNotDeleted(id)
                .map(mapper::toDomainEntity)
                .orElseThrow(() -> new CategoryNotFoundException(id));
    }

    /**
     * 카테고리 조회 (Slug)
     */
    public EcommerceCategory getCategoryBySlug(String slug) {
        return repository.findBySlug(slug)
                .filter(entity -> entity.getDeletedAt() == null)
                .map(mapper::toDomainEntity)
                .orElseThrow(() -> new CategoryNotFoundException(slug));
    }

    /**
     * 모든 카테고리 조회 (트리 구조)
     */
    public List<EcommerceCategory> getAllCategoriesTree() {
        List<EcommerceCategoryJpaEntity> allEntities = repository.findAllNotDeleted();
        List<EcommerceCategory> allCategories = allEntities.stream()
                .map(mapper::toDomainEntity)
                .collect(Collectors.toList());

        return buildTree(allCategories);
    }

    /**
     * 활성 카테고리만 조회 (트리 구조)
     */
    public List<EcommerceCategory> getActiveCategoriesTree() {
        List<EcommerceCategoryJpaEntity> activeEntities = repository.findAllActiveNotDeleted();
        List<EcommerceCategory> activeCategories = activeEntities.stream()
                .map(mapper::toDomainEntity)
                .collect(Collectors.toList());

        return buildTree(activeCategories);
    }

    /**
     * 트리 구조 구축
     */
    private List<EcommerceCategory> buildTree(List<EcommerceCategory> categories) {
        Map<Long, EcommerceCategory> categoryMap = new HashMap<>();
        List<EcommerceCategory> rootCategories = new ArrayList<>();

        // Map 생성
        for (EcommerceCategory category : categories) {
            categoryMap.put(category.getId(), category);
        }

        // 트리 구축
        for (EcommerceCategory category : categories) {
            if (category.isRoot()) {
                rootCategories.add(category);
            } else {
                EcommerceCategory parent = categoryMap.get(category.getParentId());
                if (parent != null) {
                    parent.addChild(category);
                }
            }
        }

        return rootCategories;
    }

    /**
     * 카테고리 업데이트
     */
    @Transactional
    public EcommerceCategory updateCategory(Long id, String name, String slug) {
        EcommerceCategory category = getCategoryById(id);

        // Slug 변경 시 중복 체크
        if (slug != null && !slug.equals(category.getSlug())) {
            if (repository.findBySlug(slug).isPresent()) {
                throw new DuplicateSlugException(slug);
            }
        }

        category.updateInfo(name, slug);
        EcommerceCategoryJpaEntity updated = repository.save(mapper.toJpaEntity(category));
        return mapper.toDomainEntity(updated);
    }

    /**
     * 카테고리 이동 (부모 변경)
     */
    @Transactional
    public EcommerceCategory moveCategory(Long categoryId, Long newParentId) {
        EcommerceCategory category = getCategoryById(categoryId);

        // 순환 참조 검증
        validateNoCircularReference(categoryId, newParentId);

        Integer newParentDepth;
        if (newParentId == null) {
            newParentDepth = null;
        } else {
            EcommerceCategory newParent = getCategoryById(newParentId);
            newParentDepth = newParent.getDepth();
        }

        category.changeParent(newParentId, newParentDepth != null ? newParentDepth : -1);

        EcommerceCategoryJpaEntity updated = repository.save(mapper.toJpaEntity(category));
        return mapper.toDomainEntity(updated);
    }

    /**
     * 순환 참조 검증
     */
    private void validateNoCircularReference(Long categoryId, Long newParentId) {
        if (newParentId == null) {
            return;
        }

        if (categoryId.equals(newParentId)) {
            throw new CircularReferenceException(categoryId, newParentId);
        }

        // 새 부모가 현재 카테고리의 자손인지 확인
        Set<Long> descendants = getDescendantIds(categoryId);
        if (descendants.contains(newParentId)) {
            throw new CircularReferenceException(
                    String.format("Cannot move category %d to parent %d: would create circular reference", categoryId, newParentId)
            );
        }
    }

    /**
     * 하위 카테고리 ID 모두 조회 (재귀)
     */
    private Set<Long> getDescendantIds(Long categoryId) {
        Set<Long> descendants = new HashSet<>();
        List<EcommerceCategoryJpaEntity> children = repository.findByParentId(categoryId);

        for (EcommerceCategoryJpaEntity child : children) {
            descendants.add(child.getId());
            descendants.addAll(getDescendantIds(child.getId()));
        }

        return descendants;
    }

    /**
     * 정렬 순서 변경
     */
    @Transactional
    public EcommerceCategory changeSortOrder(Long id, Integer sortOrder) {
        EcommerceCategory category = getCategoryById(id);
        category.changeSortOrder(sortOrder);
        EcommerceCategoryJpaEntity updated = repository.save(mapper.toJpaEntity(category));
        return mapper.toDomainEntity(updated);
    }

    /**
     * 카테고리 활성화
     */
    @Transactional
    public EcommerceCategory activateCategory(Long id) {
        EcommerceCategory category = getCategoryById(id);
        category.activate();
        EcommerceCategoryJpaEntity updated = repository.save(mapper.toJpaEntity(category));
        return mapper.toDomainEntity(updated);
    }

    /**
     * 카테고리 비활성화
     */
    @Transactional
    public EcommerceCategory deactivateCategory(Long id) {
        EcommerceCategory category = getCategoryById(id);
        category.deactivate();
        EcommerceCategoryJpaEntity updated = repository.save(mapper.toJpaEntity(category));
        return mapper.toDomainEntity(updated);
    }

    /**
     * 카테고리 삭제 (soft delete)
     */
    @Transactional
    public void deleteCategory(Long id) {
        EcommerceCategory category = getCategoryById(id);

        // 하위 카테고리 존재 확인
        long childCount = repository.countChildrenByParentId(id);
        if (childCount > 0) {
            throw new CategoryHasChildrenException(id, childCount);
        }

        // 연결된 상품 존재 확인
        if (repository.hasProducts(id)) {
            throw new CategoryHasProductsException(id);
        }

        category.softDelete();
        repository.save(mapper.toJpaEntity(category));
    }
}
