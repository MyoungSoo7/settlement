package github.lms.lemuel.product.application.port.in;

import github.lms.lemuel.product.domain.Category;

import java.util.List;

public interface CategoryUseCase {
    Category createCategory(String name, String description, Long parentId, Integer displayOrder);
    Category getCategoryById(Long id);
    List<Category> getAllCategories();
    List<Category> getActiveCategories();
    List<Category> getRootCategories();
    List<Category> getSubCategories(Long parentId);
    Category updateCategory(Long id, String name, String description, Integer displayOrder);
    void activateCategory(Long id);
    void deactivateCategory(Long id);
}
