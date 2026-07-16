package github.lms.lemuel.investment.adapter.in.web.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * 투자 주문 신청 요청.
 *
 * <p>셀러 식별자는 요청 바디로 받지 않는다 — 서버가 JWT 인증 주체에서 파생한다(IDOR 방지).
 * 클라이언트가 보내는 잉여 {@code sellerId} 필드는 무시된다.
 *
 * <p>{@code amount} 는 NUMERIC(19,2) 저장 정밀도에 맞춰 정수 17자리·소수 2자리로 제한한다
 * ({@code @Digits}) — 소수 3자리 이상 입력은 웹 계약 400 으로 조기 차단하고, 통과한 값은
 * 도메인 진입 시 scale 2 HALF_UP 로 정규화된다.
 */
public record PlaceInvestmentOrderRequest(
        @NotNull @Pattern(regexp = "\\d{6}", message = "stockCode 는 6자리 숫자여야 합니다") String stockCode,
        @NotNull @Positive @Digits(integer = 17, fraction = 2,
                message = "amount 는 정수 17자리·소수 2자리 이내여야 합니다") BigDecimal amount) {
}
