package github.lms.lemuel.category.application.port.out;

import github.lms.lemuel.category.domain.EcommerceCategory;

/**
 * 카테고리 저장 Outbound Port
 */
public interface SaveEcommerceCategoryPort {

    EcommerceCategory save(EcommerceCategory category);
}
