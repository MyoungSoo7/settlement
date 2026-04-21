package github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 승인 상태 추적 DTO
 * WAITING_APPROVAL, APPROVED, REJECTED 상태의 정산 건을 추적
 */
@Getter
@AllArgsConstructor
public class ApprovalStatusDto {
    private final Long settlementId;
    private final Long orderId;
    private final Long paymentId;
    private final BigDecimal netAmount;
    private final String status;
    private final LocalDate settlementDate;
    private final String ordererEmail;
}
