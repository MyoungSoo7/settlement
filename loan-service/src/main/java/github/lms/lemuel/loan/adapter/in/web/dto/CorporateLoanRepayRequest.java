package github.lms.lemuel.loan.adapter.in.web.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * 기업 신용대출 상환 요청 본문. 상환액만 받는다 — 대출 식별자는 경로변수, 소유권은 JWT 주체에서 파생한다.
 * 금액 형식 검증(양수·자릿수)은 여기서, 잔액 대비 clamp·상태 검증은 도메인에서 강제한다.
 */
public record CorporateLoanRepayRequest(
        @NotNull(message = "상환액은 필수입니다")
        @Positive(message = "상환액은 양수여야 합니다")
        @Digits(integer = 17, fraction = 2, message = "상환액 자릿수가 허용 범위를 벗어났습니다")
        BigDecimal amount) {
}
