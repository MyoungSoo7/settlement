package github.lms.lemuel.payout.adapter.out.persistence;

import github.lms.lemuel.payout.domain.Payout;
import github.lms.lemuel.payout.domain.PayoutStatus;

public final class PayoutMapper {
    private PayoutMapper() {}

    public static PayoutJpaEntity toJpa(Payout d) {
        return PayoutJpaEntity.builder()
                .id(d.getId())
                .settlementId(d.getSettlementId())
                .sellerId(d.getSellerId())
                .amount(d.getAmount())
                .status(d.getStatus().name())
                .bankTransactionId(d.getBankTransactionId())
                .failureReason(d.getFailureReason())
                .requestedAt(d.getRequestedAt())
                .completedAt(d.getCompletedAt())
                .createdAt(d.getCreatedAt())
                .updatedAt(d.getUpdatedAt())
                .build();
    }

    public static Payout toDomain(PayoutJpaEntity e) {
        return new Payout(
                e.getId(), e.getSettlementId(), e.getSellerId(), e.getAmount(),
                PayoutStatus.valueOf(e.getStatus()),
                e.getBankTransactionId(), e.getFailureReason(),
                e.getRequestedAt(), e.getCompletedAt(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
