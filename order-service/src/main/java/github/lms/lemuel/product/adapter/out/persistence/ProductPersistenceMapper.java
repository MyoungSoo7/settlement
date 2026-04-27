package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.domain.Product;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface ProductPersistenceMapper {

    Product toDomain(ProductJpaEntity entity);

    ProductJpaEntity toEntity(Product domain);
}
