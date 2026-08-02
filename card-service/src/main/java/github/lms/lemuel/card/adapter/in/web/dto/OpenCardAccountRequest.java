package github.lms.lemuel.card.adapter.in.web.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 카드계정 개설 요청.
 *
 * <p>★ 요청자(userId)를 본문에 두지 않는다 — JWT 주체에서만 파생한다. 본문의 사용자 식별자를
 * 믿으면 남의 조직 OWNER 를 사칭할 수 있다(IDOR).
 */
public record OpenCardAccountRequest(@NotNull(message = "organizationId 는 필수입니다") Long organizationId) {
}
