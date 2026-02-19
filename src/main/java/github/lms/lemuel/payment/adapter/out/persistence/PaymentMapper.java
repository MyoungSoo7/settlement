package github.lms.lemuel.payment.adapter.out.persistence;

import github.lms.lemuel.payment.domain.Payment;
import github.lms.lemuel.payment.domain.PaymentStatus;
import org.springframework.stereotype.Component;

/**
 * Mapper between Domain Payment and JPA PaymentJpaEntity
 */
@Component
public class PaymentMapper {

    /**
     * Map JPA entity to domain model
     */
    public Payment toDomain(PaymentJpaEntity entity) {
        return new Payment(
            entity.getId(),
            entity.getOrderId(),
            entity.getAmount(),
            entity.getRefundedAmount(),
            PaymentStatus.valueOf(entity.getStatus()),
            entity.getPaymentMethod(),
            entity.getPgTransactionId(),
            entity.getCapturedAt(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    /**
     * Map domain model to JPA entity
     */
    public PaymentJpaEntity toJpaEntity(Payment payment) {
        PaymentJpaEntity entity = new PaymentJpaEntity();
        entity.setId(payment.getId());
        entity.setOrderId(payment.getOrderId());
        entity.setAmount(payment.getAmount());
        entity.setRefundedAmount(payment.getRefundedAmount());
        entity.setStatus(payment.getStatus().name());
        entity.setPaymentMethod(payment.getPaymentMethod());
        entity.setPgTransactionId(payment.getPgTransactionId());
        entity.setCapturedAt(payment.getCapturedAt());
        entity.setCreatedAt(payment.getCreatedAt());
        entity.setUpdatedAt(payment.getUpdatedAt());
        return entity;
    }
}
