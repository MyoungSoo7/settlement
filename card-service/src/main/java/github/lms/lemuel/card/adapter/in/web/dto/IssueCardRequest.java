package github.lms.lemuel.card.adapter.in.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * 카드 발급 요청.
 *
 * <p>{@code holderUserId}(발급 대상)는 본문에 있는 것이 정상이다 — 대표가 <b>임직원에게</b> 발급하는
 * 행위라 대상은 요청자와 다를 수밖에 없다. 반면 요청자는 절대 본문에서 오지 않는다(JWT 주체에서만
 * 파생). 대상은 조직 멤버십 프로젝션으로 검증되므로 임의의 userId 를 넣어도 남의 조직에는 닿지 않는다.
 */
public record IssueCardRequest(
        @NotNull Long holderUserId,
        @NotNull @PositiveOrZero BigDecimal subLimit) {
}
