package github.lms.lemuel.settlement.adapter.out.persistence;

import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementStatus;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/**
 * Domain <-> JpaEntity 매핑 (MapStruct)
 */
@Mapper(componentModel = "spring", imports = SettlementStatus.class)
public interface SettlementPersistenceMapper {

    @Mapping(target = "status", expression = "java(SettlementStatus.fromString(entity.getStatus()))")
    @Mapping(target = "commissionRate", ignore = true)
    Settlement toDomain(SettlementJpaEntity entity);

    /**
     * commissionRate 는 write-once 스냅샷이라 setter 가 없다 — rehydration 전용 메서드로 복원.
     */
    @AfterMapping
    default void rehydrateCommissionRate(SettlementJpaEntity entity, @MappingTarget Settlement settlement) {
        settlement.rehydrateCommissionRate(entity.getCommissionRate());
    }

    @Mapping(target = "status", expression = "java(domain.getStatus().name())")
    SettlementJpaEntity toEntity(Settlement domain);
}
