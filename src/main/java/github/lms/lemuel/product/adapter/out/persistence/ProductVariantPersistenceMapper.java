package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.domain.ProductOption;
import github.lms.lemuel.product.domain.ProductOptionValue;
import github.lms.lemuel.product.domain.ProductVariant;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ProductVariantPersistenceMapper {

    // ProductOption -> JPA
    public ProductOptionJpaEntity toOptionJpaEntity(ProductOption option) {
        if (option == null) {
            return null;
        }
        return new ProductOptionJpaEntity(
                option.getId(),
                option.getProductId(),
                option.getName(),
                option.getSortOrder(),
                option.getCreatedAt(),
                option.getUpdatedAt()
        );
    }

    // JPA -> ProductOption (values 별도 세팅)
    public ProductOption toOptionDomain(ProductOptionJpaEntity jpaEntity, List<ProductOptionValue> values) {
        if (jpaEntity == null) {
            return null;
        }
        return new ProductOption(
                jpaEntity.getId(),
                jpaEntity.getProductId(),
                jpaEntity.getName(),
                jpaEntity.getSortOrder(),
                values,
                jpaEntity.getCreatedAt(),
                jpaEntity.getUpdatedAt()
        );
    }

    // ProductOptionValue -> JPA
    public ProductOptionValueJpaEntity toOptionValueJpaEntity(ProductOptionValue value) {
        if (value == null) {
            return null;
        }
        return new ProductOptionValueJpaEntity(
                value.getId(),
                value.getOptionId(),
                value.getValue(),
                value.getSortOrder(),
                value.getCreatedAt()
        );
    }

    // JPA -> ProductOptionValue
    public ProductOptionValue toOptionValueDomain(ProductOptionValueJpaEntity jpaEntity) {
        if (jpaEntity == null) {
            return null;
        }
        return new ProductOptionValue(
                jpaEntity.getId(),
                jpaEntity.getOptionId(),
                jpaEntity.getValue(),
                jpaEntity.getSortOrder(),
                jpaEntity.getCreatedAt()
        );
    }

    // ProductVariant -> JPA
    public ProductVariantJpaEntity toVariantJpaEntity(ProductVariant variant) {
        if (variant == null) {
            return null;
        }
        return new ProductVariantJpaEntity(
                variant.getId(),
                variant.getProductId(),
                variant.getSku(),
                variant.getPrice(),
                variant.getStockQuantity(),
                variant.getOptionValues(),
                variant.getIsActive(),
                variant.getCreatedAt(),
                variant.getUpdatedAt()
        );
    }

    // JPA -> ProductVariant
    public ProductVariant toVariantDomain(ProductVariantJpaEntity jpaEntity) {
        if (jpaEntity == null) {
            return null;
        }
        return new ProductVariant(
                jpaEntity.getId(),
                jpaEntity.getProductId(),
                jpaEntity.getSku(),
                jpaEntity.getPrice(),
                jpaEntity.getStockQuantity(),
                jpaEntity.getOptionValues(),
                jpaEntity.isActive(),
                jpaEntity.getCreatedAt(),
                jpaEntity.getUpdatedAt()
        );
    }
}
