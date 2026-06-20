package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.domain.Category;
import org.springframework.stereotype.Component;

@Component
public class CategoryMapper {

    public CategoryJpaEntity toJpaEntity(Category category) {
        if (category == null) {
            return null;
        }

        return new CategoryJpaEntity(
                category.getId(),
                category.getName(),
                category.getDescription(),
                category.getParentId(),
                category.getDisplayOrder(),
                category.getIsActive(),
                category.getCreatedAt(),
                category.getUpdatedAt()
        );
    }

    public Category toDomainEntity(CategoryJpaEntity jpaEntity) {
        if (jpaEntity == null) {
            return null;
        }

        return new Category(
                jpaEntity.getId(),
                jpaEntity.getName(),
                jpaEntity.getDescription(),
                jpaEntity.getParentId(),
                jpaEntity.getDisplayOrder(),
                jpaEntity.getIsActive(),
                jpaEntity.getCreatedAt(),
                jpaEntity.getUpdatedAt()
        );
    }
}
