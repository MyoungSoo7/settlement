package github.lms.lemuel.reservation.adapter.out.persistence;

import github.lms.lemuel.reservation.domain.Reservation;
import github.lms.lemuel.reservation.domain.ReservationStatus;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Reservation Domain <-> JpaEntity 매핑 (MapStruct)
 */
@Mapper(componentModel = "spring", imports = ReservationStatus.class)
public interface ReservationPersistenceMapper {

    @Mapping(target = "status", expression = "java(ReservationStatus.fromString(entity.getStatus()))")
    Reservation toDomain(ReservationJpaEntity entity);

    @Mapping(target = "status", expression = "java(domain.getStatus().name())")
    ReservationJpaEntity toEntity(Reservation domain);
}
