package github.lms.lemuel.product.application.port.out;

import github.lms.lemuel.product.domain.Category;

import java.util.List;
import java.util.Optional;

public interface LoadCategoryPort {
    Optional<Category> findById(Long id);
    Optional<Category> findByName(String name);
    List<Category> findAll();
    List<Category> findActiveCategories();
    List<Category> findRootCategories();
    List<Category> findSubCategories(Long parentId);
}
