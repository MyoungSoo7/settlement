package github.lms.lemuel.card.adapter.in.web.dto;

import github.lms.lemuel.card.domain.CardStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 카드 상태 변경 요청(정지·재개·해지).
 *
 * <p>전이별 엔드포인트(/suspend, /resume, /cancel)를 두지 않고 <b>목표 상태를 값으로</b> 받는다 —
 * 허용 전이의 정본은 {@code CardStatus.canTransitionTo} 하나여야 하는데, 엔드포인트를 쪼개면
 * URL 자체가 두 번째 정본이 되어 도메인 규칙이 바뀔 때 REST 표면이 조용히 뒤처진다.
 *
 * <p>{@code reason} 이 필수인 이유는 감사다 — 카드 정지·해지는 사후에 "누가 왜"를 재현할 수 있어야
 * 한다. 유스케이스도 같은 검증을 하지만(이벤트 소비 경로는 컨트롤러를 거치지 않는다) 표면에서
 * 400 으로 먼저 끊어 준다. 200자 상한은 status_changed 계약 스키마와 같은 값이다.
 */
public record ChangeCardStatusRequest(
        @NotNull CardStatus status,
        @NotBlank @Size(max = 200) String reason) {
}
