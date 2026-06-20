package github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * 정산 대사 (Reconciliation) DTO
 * 결제 금액과 정산 금액 간 불일치를 탐지
 */
@Getter
@AllArgsConstructor
public class SettlementReconciliationDto {
    private final Long settlementId;
    private final Long paymentId;
    private final BigDecimal paymentAmount;
    private final BigDecimal settlementPaymentAmount;
    private final BigDecimal paymentRefundedAmount;
    private final BigDecimal settlementRefundedAmount;
    private final BigDecimal amountDifference;
    private final String paymentStatus;
    private final String settlementStatus;
}
