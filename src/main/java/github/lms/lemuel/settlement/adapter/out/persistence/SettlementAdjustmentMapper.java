package github.lms.lemuel.settlement.adapter.out.persistence;

import github.lms.lemuel.settlement.domain.SettlementAdjustment;
import github.lms.lemuel.settlement.domain.SettlementAdjustmentStatus;

public final class SettlementAdjustmentMapper {
    private SettlementAdjustmentMapper() {}

    public static SettlementAdjustmentJpaEntity toJpa(SettlementAdjustment d) {
        return SettlementAdjustmentJpaEntity.builder()
                .id(d.getId())
                .settlementId(d.getSettlementId())
                .refundId(d.getRefundId())
                .amount(d.getAmount().negate())   // DB는 음수
                .status(d.getStatus().name())
                .adjustmentDate(d.getAdjustmentDate())
                .confirmedAt(d.getConfirmedAt())
                .createdAt(d.getCreatedAt())
                .updatedAt(d.getUpdatedAt())
                .build();
    }

    public static SettlementAdjustment toDomain(SettlementAdjustmentJpaEntity e) {
        return new SettlementAdjustment(
                e.getId(), e.getSettlementId(), e.getRefundId(),
                e.getAmount().abs(),
                SettlementAdjustmentStatus.valueOf(e.getStatus()),
                e.getAdjustmentDate(), e.getConfirmedAt(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
