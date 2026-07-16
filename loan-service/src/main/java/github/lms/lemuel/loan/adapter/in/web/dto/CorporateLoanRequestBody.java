package github.lms.lemuel.loan.adapter.in.web.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * 기업 신용대출 신청 요청.
 */
public record CorporateLoanRequestBody(
        @NotBlank @Pattern(regexp = "\\d{6}", message = "종목코드는 6자리 숫자여야 합니다") String stockCode,
        @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal principal,
        @Positive @Max(3650) int termDays) {
}
