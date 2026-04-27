package github.lms.lemuel.order.adapter.out.persistence;

import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderStatus;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Domain <-> JpaEntity 매핑 (MapStruct)
 */
@Mapper(componentModel = "spring", imports = OrderStatus.class)
public interface OrderPersistenceMapper {

    @Mapping(target = "status", expression = "java(OrderStatus.fromString(entity.getStatus()))")
    Order toDomain(OrderJpaEntity entity);

    @Mapping(target = "status", expression = "java(domain.getStatus().name())")
    OrderJpaEntity toEntity(Order domain);
}
