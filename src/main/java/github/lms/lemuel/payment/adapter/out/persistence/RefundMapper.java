package github.lms.lemuel.payment.adapter.out.persistence;

import github.lms.lemuel.payment.domain.Refund;
import github.lms.lemuel.payment.domain.RefundStatus;

public final class RefundMapper {
    private RefundMapper() {}

    public static RefundJpaEntity toJpa(Refund domain) {
        return RefundJpaEntity.builder()
                .id(domain.getId())
                .paymentId(domain.getPaymentId())
                .amount(domain.getAmount())
                .status(domain.getStatus().name())
                .reason(domain.getReason())
                .idempotencyKey(domain.getIdempotencyKey())
                .requestedAt(domain.getRequestedAt())
                .completedAt(domain.getCompletedAt())
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }

    public static Refund toDomain(RefundJpaEntity jpa) {
        return new Refund(
                jpa.getId(),
                jpa.getPaymentId(),
                jpa.getAmount(),
                RefundStatus.valueOf(jpa.getStatus()),
                jpa.getReason(),
                jpa.getIdempotencyKey(),
                jpa.getRequestedAt(),
                jpa.getCompletedAt(),
                jpa.getCreatedAt(),
                jpa.getUpdatedAt()
        );
    }
}
