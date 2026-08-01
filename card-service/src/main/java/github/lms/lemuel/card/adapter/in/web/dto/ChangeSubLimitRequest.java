package github.lms.lemuel.card.adapter.in.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * 서브한도 변경 요청.
 *
 * <p>카드는 경로에서, 요청자는 JWT 에서 온다 — 본문에는 <b>바꿀 값 하나만</b> 있다.
 * 카드계정을 함께 받지 않는 이유는 카드 하나로 이미 결정되는 값이기 때문이다: 두 번 받으면
 * "경로의 계정과 카드의 실제 계정이 다를 때"라는 검증해야 할 경우의 수가 공짜로 하나 늘어난다.
 */
public record ChangeSubLimitRequest(
        @NotNull @PositiveOrZero BigDecimal subLimit) {
}
