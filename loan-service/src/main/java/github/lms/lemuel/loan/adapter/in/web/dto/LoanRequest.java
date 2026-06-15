package github.lms.lemuel.loan.adapter.in.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * 선정산 대출 신청 요청.
 */
public record LoanRequest(
        @NotNull Long sellerId,
        @NotNull @Positive BigDecimal principal,
        @Min(0) int financingDays) {
}
