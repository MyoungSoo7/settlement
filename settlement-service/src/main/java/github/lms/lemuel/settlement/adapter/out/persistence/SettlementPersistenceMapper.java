package github.lms.lemuel.settlement.adapter.out.persistence;

import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementStatus;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Domain <-> JpaEntity 매핑 (MapStruct)
 */
@Mapper(componentModel = "spring", imports = SettlementStatus.class)
public interface SettlementPersistenceMapper {

    @Mapping(target = "status", expression = "java(SettlementStatus.fromString(entity.getStatus()))")
    Settlement toDomain(SettlementJpaEntity entity);

    @Mapping(target = "status", expression = "java(domain.getStatus().name())")
    SettlementJpaEntity toEntity(Settlement domain);
}
