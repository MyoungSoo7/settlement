package github.lms.lemuel.investment.adapter.in.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * 투자 주문 신청 요청.
 *
 * <p>셀러 식별자는 요청 바디로 받지 않는다 — 서버가 JWT 인증 주체에서 파생한다(IDOR 방지).
 * 클라이언트가 보내는 잉여 {@code sellerId} 필드는 무시된다.
 */
public record PlaceInvestmentOrderRequest(
        @NotNull @Pattern(regexp = "\\d{6}", message = "stockCode 는 6자리 숫자여야 합니다") String stockCode,
        @NotNull @Positive BigDecimal amount) {
}
