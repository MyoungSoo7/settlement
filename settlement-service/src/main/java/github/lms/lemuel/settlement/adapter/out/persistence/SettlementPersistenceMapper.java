package github.lms.lemuel.settlement.adapter.out.persistence;

import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementStatus;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Domain <-> JpaEntity 매핑 (MapStruct)
 */
@Mapper(componentModel = "spring")
public interface SettlementPersistenceMapper {

    /**
     * Entity → Domain 복원. 모든 필드(commissionRate·holdback·version 포함)를 {@link Settlement#rehydrate}
     * 팩토리로 재구성한다. commissionRate 는 정산 시점 write-once 스냅샷이라 setter 가 없고, private 생성 경로
     * + rehydrate 만으로 복원해 이력 보존 불변식을 도메인이 강제한다.
     */
    default Settlement toDomain(SettlementJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        return Settlement.rehydrate(
                entity.getId(),
                entity.getPaymentId(),
                entity.getOrderId(),
                entity.getPaymentAmount(),
                entity.getRefundedAmount(),
                entity.getCommission(),
                entity.getCommissionRate(),
                entity.getNetAmount(),
                SettlementStatus.fromString(entity.getStatus()),
                entity.getSettlementDate(),
                entity.getFailureReason(),
                entity.getConfirmedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion(),
                entity.getHoldbackAmount(),
                entity.getHoldbackRate(),
                entity.getHoldbackReleaseDate(),
                entity.isHoldbackReleased(),
                entity.getHoldbackReleasedAt());
    }

    @Mapping(target = "status", expression = "java(domain.getStatus().name())")
    SettlementJpaEntity toEntity(Settlement domain);
}
