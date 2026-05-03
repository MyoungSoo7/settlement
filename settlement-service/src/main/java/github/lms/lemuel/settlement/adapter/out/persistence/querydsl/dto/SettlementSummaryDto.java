package github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 일별/월별 정산 요약 집계 DTO
 * QueryDSL Projections.constructor()로 직접 매핑
 */
@Getter
@AllArgsConstructor
public class SettlementSummaryDto {
    private final LocalDate settlementDate;
    private final long totalCount;
    private final BigDecimal totalPaymentAmount;
    private final BigDecimal totalRefundedAmount;
    private final BigDecimal totalCommission;
    private final BigDecimal totalNetAmount;
    private final long doneCount;
    private final long failedCount;
    private final long canceledCount;
}
