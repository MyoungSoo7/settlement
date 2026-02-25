package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.CategoryUseCase;
import github.lms.lemuel.product.application.port.out.LoadCategoryPort;
import github.lms.lemuel.product.application.port.out.SaveCategoryPort;
import github.lms.lemuel.product.domain.Category;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryService implements CategoryUseCase {

    private final SaveCategoryPort saveCategoryPort;
    private final LoadCategoryPort loadCategoryPort;

    @Override
    @Transactional
    public Category createCategory(String name, String description, Long parentId, Integer displayOrder) {
        Category category;
        if (parentId == null) {
            category = Category.create(name, description, displayOrder);
        } else {
            category = Category.createSubCategory(name, description, parentId, displayOrder);
        }
        return saveCategoryPort.save(category);
    }

    @Override
    public Category getCategoryById(Long id) {
        return loadCategoryPort.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Category not found: " + id));
    }

    @Override
    public List<Category> getAllCategories() {
        return loadCategoryPort.findAll();
    }

    @Override
    public List<Category> getActiveCategories() {
        return loadCategoryPort.findActiveCategories();
    }

    @Override
    public List<Category> getRootCategories() {
        return loadCategoryPort.findRootCategories();
    }

    @Override
    public List<Category> getSubCategories(Long parentId) {
        return loadCategoryPort.findSubCategories(parentId);
    }

    @Override
    @Transactional
    public Category updateCategory(Long id, String name, String description, Integer displayOrder) {
        Category category = getCategoryById(id);
        category.updateInfo(name, description);
        if (displayOrder != null) {
            category.changeDisplayOrder(displayOrder);
        }
        return saveCategoryPort.save(category);
    }

    @Override
    @Transactional
    public void activateCategory(Long id) {
        Category category = getCategoryById(id);
        category.activate();
        saveCategoryPort.save(category);
    }

    @Override
    @Transactional
    public void deactivateCategory(Long id) {
        Category category = getCategoryById(id);
        category.deactivate();
        saveCategoryPort.save(category);
    }
}
