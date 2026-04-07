package github.lms.lemuel.returns.adapter.out.persistence;

import github.lms.lemuel.returns.domain.ReturnOrder;
import github.lms.lemuel.returns.domain.ReturnReason;
import github.lms.lemuel.returns.domain.ReturnStatus;
import github.lms.lemuel.returns.domain.ReturnType;

/**
 * Domain <-> JpaEntity 수동 매핑
 */
public class ReturnPersistenceMapper {

    private ReturnPersistenceMapper() {
        // utility class
    }

    public static ReturnOrder toDomain(ReturnOrderJpaEntity entity) {
        ReturnOrder domain = new ReturnOrder();
        domain.setId(entity.getId());
        domain.setOrderId(entity.getOrderId());
        domain.setUserId(entity.getUserId());
        domain.setType(ReturnType.fromString(entity.getType()));
        domain.setStatus(ReturnStatus.fromString(entity.getStatus()));
        domain.setReason(ReturnReason.fromString(entity.getReason()));
        domain.setReasonDetail(entity.getReasonDetail());
        domain.setRefundAmount(entity.getRefundAmount());
        domain.setExchangeOrderId(entity.getExchangeOrderId());
        domain.setTrackingNumber(entity.getTrackingNumber());
        domain.setCarrier(entity.getCarrier());
        domain.setApprovedAt(entity.getApprovedAt());
        domain.setReceivedAt(entity.getReceivedAt());
        domain.setCompletedAt(entity.getCompletedAt());
        domain.setRejectedAt(entity.getRejectedAt());
        domain.setRejectionReason(entity.getRejectionReason());
        domain.setCreatedAt(entity.getCreatedAt());
        domain.setUpdatedAt(entity.getUpdatedAt());
        return domain;
    }

    public static ReturnOrderJpaEntity toEntity(ReturnOrder domain) {
        ReturnOrderJpaEntity entity = new ReturnOrderJpaEntity();
        entity.setId(domain.getId());
        entity.setOrderId(domain.getOrderId());
        entity.setUserId(domain.getUserId());
        entity.setType(domain.getType().name());
        entity.setStatus(domain.getStatus().name());
        entity.setReason(domain.getReason().name());
        entity.setReasonDetail(domain.getReasonDetail());
        entity.setRefundAmount(domain.getRefundAmount());
        entity.setExchangeOrderId(domain.getExchangeOrderId());
        entity.setTrackingNumber(domain.getTrackingNumber());
        entity.setCarrier(domain.getCarrier());
        entity.setApprovedAt(domain.getApprovedAt());
        entity.setReceivedAt(domain.getReceivedAt());
        entity.setCompletedAt(domain.getCompletedAt());
        entity.setRejectedAt(domain.getRejectedAt());
        entity.setRejectionReason(domain.getRejectionReason());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }
}
