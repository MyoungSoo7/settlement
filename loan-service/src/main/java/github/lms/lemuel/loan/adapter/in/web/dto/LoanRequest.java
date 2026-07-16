package github.lms.lemuel.loan.adapter.in.web.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * 선정산 대출 신청 요청.
 *
 * <p>신청 셀러(sellerId)는 요청 바디가 아니라 JWT 인증 주체에서 파생한다(IDOR 방지 가드레일) —
 * 타인 명의 신청을 원천 차단하기 위해 바디에서 sellerId 를 받지 않는다.
 */
public record LoanRequest(
        @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal principal,
        @Min(0) @Max(365) int financingDays) {
}
