package github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * 결제/환불 집계 DTO
 * 기간별 결제 총액, 환불 총액, 순 정산액을 집계
 */
@Getter
@AllArgsConstructor
public class PaymentRefundAggregationDto {
    private final long totalPaymentCount;
    private final BigDecimal totalPaymentAmount;
    private final long refundedPaymentCount;
    private final BigDecimal totalRefundedAmount;
    private final BigDecimal totalCommission;
    private final BigDecimal totalNetAmount;
    private final BigDecimal refundRate;
}
