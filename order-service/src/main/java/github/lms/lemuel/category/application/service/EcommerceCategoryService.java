package github.lms.lemuel.category.application.service;

import github.lms.lemuel.category.application.port.out.LoadEcommerceCategoryPort;
import github.lms.lemuel.category.application.port.out.SaveEcommerceCategoryPort;
import github.lms.lemuel.category.domain.EcommerceCategory;
import github.lms.lemuel.category.domain.exception.CategoryHasChildrenException;
import github.lms.lemuel.category.domain.exception.CategoryHasProductsException;
import github.lms.lemuel.category.domain.exception.CategoryNotFoundException;
import github.lms.lemuel.category.domain.exception.CircularReferenceException;
import github.lms.lemuel.category.domain.exception.DuplicateSlugException;
import github.lms.lemuel.category.util.SlugGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EcommerceCategoryService {

    private final LoadEcommerceCategoryPort loadPort;
    private final SaveEcommerceCategoryPort savePort;
    private final SlugGenerator slugGenerator;

    @Transactional
    public EcommerceCategory createCategory(String name, String slug, Long parentId, Integer sortOrder) {
        if (slug == null || slug.trim().isEmpty()) {
            if (parentId != null) {
                EcommerceCategory parent = getCategoryById(parentId);
                slug = slugGenerator.generateWithParent(parent.getSlug(), name);
            } else {
                slug = slugGenerator.generate(name);
            }
        }

        if (loadPort.findBySlug(slug).isPresent()) {
            throw new DuplicateSlugException(slug);
        }

        EcommerceCategory category;
        if (parentId == null) {
            category = EcommerceCategory.createRoot(name, slug, sortOrder != null ? sortOrder : 0);
        } else {
            EcommerceCategory parent = getCategoryById(parentId);
            if (parent.isDeleted()) {
                throw new IllegalArgumentException("Cannot create category under deleted parent");
            }
            category = EcommerceCategory.createChild(name, slug, parentId, parent.getDepth(),
                    sortOrder != null ? sortOrder : 0);
        }

        return savePort.save(category);
    }

    public EcommerceCategory getCategoryById(Long id) {
        return loadPort.findByIdNotDeleted(id)
                .orElseThrow(() -> new CategoryNotFoundException(id));
    }

    public EcommerceCategory getCategoryBySlug(String slug) {
        return loadPort.findBySlug(slug)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new CategoryNotFoundException(slug));
    }

    public List<EcommerceCategory> getAllCategoriesTree() {
        return buildTree(loadPort.findAllNotDeleted());
    }

    public List<EcommerceCategory> getActiveCategoriesTree() {
        return buildTree(loadPort.findAllActiveNotDeleted());
    }

    private List<EcommerceCategory> buildTree(List<EcommerceCategory> categories) {
        Map<Long, EcommerceCategory> categoryMap = new HashMap<>();
        List<EcommerceCategory> rootCategories = new ArrayList<>();

        for (EcommerceCategory category : categories) {
            categoryMap.put(category.getId(), category);
        }

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

    @Transactional
    public EcommerceCategory updateCategory(Long id, String name, String slug) {
        EcommerceCategory category = getCategoryById(id);

        if (slug != null && !slug.equals(category.getSlug())) {
            if (loadPort.findBySlug(slug).isPresent()) {
                throw new DuplicateSlugException(slug);
            }
        }

        category.updateInfo(name, slug);
        return savePort.save(category);
    }

    @Transactional
    public EcommerceCategory moveCategory(Long categoryId, Long newParentId) {
        EcommerceCategory category = getCategoryById(categoryId);

        validateNoCircularReference(categoryId, newParentId);

        Integer newParentDepth;
        if (newParentId == null) {
            newParentDepth = null;
        } else {
            EcommerceCategory newParent = getCategoryById(newParentId);
            newParentDepth = newParent.getDepth();
        }

        category.changeParent(newParentId, newParentDepth != null ? newParentDepth : -1);
        return savePort.save(category);
    }

    private void validateNoCircularReference(Long categoryId, Long newParentId) {
        if (newParentId == null) {
            return;
        }
        if (categoryId.equals(newParentId)) {
            throw new CircularReferenceException(categoryId, newParentId);
        }

        Set<Long> descendants = getDescendantIds(categoryId);
        if (descendants.contains(newParentId)) {
            throw new CircularReferenceException(
                    String.format("Cannot move category %d to parent %d: would create circular reference",
                            categoryId, newParentId));
        }
    }

    private Set<Long> getDescendantIds(Long categoryId) {
        Set<Long> descendants = new HashSet<>();
        List<EcommerceCategory> children = loadPort.findByParentId(categoryId);

        for (EcommerceCategory child : children) {
            descendants.add(child.getId());
            descendants.addAll(getDescendantIds(child.getId()));
        }
        return descendants;
    }

    @Transactional
    public EcommerceCategory changeSortOrder(Long id, Integer sortOrder) {
        EcommerceCategory category = getCategoryById(id);
        category.changeSortOrder(sortOrder);
        return savePort.save(category);
    }

    @Transactional
    public EcommerceCategory activateCategory(Long id) {
        EcommerceCategory category = getCategoryById(id);
        category.activate();
        return savePort.save(category);
    }

    @Transactional
    public EcommerceCategory deactivateCategory(Long id) {
        EcommerceCategory category = getCategoryById(id);
        category.deactivate();
        return savePort.save(category);
    }

    @Transactional
    public void deleteCategory(Long id) {
        EcommerceCategory category = getCategoryById(id);

        long childCount = loadPort.countChildrenByParentId(id);
        if (childCount > 0) {
            throw new CategoryHasChildrenException(id, childCount);
        }

        if (loadPort.hasProducts(id)) {
            throw new CategoryHasProductsException(id);
        }

        category.softDelete();
        savePort.save(category);
    }
}
