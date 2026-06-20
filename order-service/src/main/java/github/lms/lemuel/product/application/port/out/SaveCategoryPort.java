package github.lms.lemuel.product.application.port.out;

import github.lms.lemuel.product.domain.Category;

public interface SaveCategoryPort {
    Category save(Category category);
}
