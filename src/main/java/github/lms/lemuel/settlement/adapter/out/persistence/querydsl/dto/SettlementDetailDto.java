package github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 정산 상세 조회 DTO (주문/결제/상품 JOIN)
 * N+1 방지: 단일 쿼리로 모든 필요한 필드를 프로젝션
 */
@Getter
@AllArgsConstructor
public class SettlementDetailDto {
    private final Long settlementId;
    private final BigDecimal paymentAmount;
    private final BigDecimal refundedAmount;
    private final BigDecimal commission;
    private final BigDecimal netAmount;
    private final String status;
    private final LocalDate settlementDate;
    private final LocalDateTime confirmedAt;
    private final LocalDateTime createdAt;
    private final Long orderId;
    private final Long userId;
    private final Long paymentId;
    private final String paymentMethod;
    private final String paymentStatus;
    private final String ordererEmail;
    private final String productName;
    private final boolean isRefunded;
}
