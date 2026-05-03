package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.domain.Product;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface ProductPersistenceMapper {

    @Mapping(target = "categoryId", ignore = true)
    @Mapping(target = "tagIds", ignore = true)
    Product toDomain(ProductJpaEntity entity);

    ProductJpaEntity toEntity(Product domain);
}
