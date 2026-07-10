package github.lms.lemuel.investment.adapter.in.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/** 투자 주문 신청 요청. */
public record PlaceInvestmentOrderRequest(
        @NotNull Long sellerId,
        @NotNull @Pattern(regexp = "\\d{6}", message = "stockCode 는 6자리 숫자여야 합니다") String stockCode,
        @NotNull @Positive BigDecimal amount) {
}
