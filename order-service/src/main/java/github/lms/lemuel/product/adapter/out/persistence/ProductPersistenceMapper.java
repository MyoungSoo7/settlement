package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.domain.Product;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface ProductPersistenceMapper {

    /**
     * Entity → Domain 복원. no-arg + setter 대신 {@link Product#rehydrate} 팩토리로 재구성해
     * 도메인 봉인을 매퍼가 우회하지 못하게 한다. tagIds 는 별도 로드 경로가 채운다(여기선 빈 목록).
     */
    default Product toDomain(ProductJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        return Product.rehydrate(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getPrice(),
                entity.getStockQuantity(),
                entity.getStatus(),
                entity.getCategoryId(),
                null,
                entity.getOptionsJson(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    ProductJpaEntity toEntity(Product domain);
}
