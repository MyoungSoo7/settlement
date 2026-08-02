package github.lms.lemuel.card.application.port.in;

import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardStatus;

/**
 * 임직원 카드의 상태 변경(정지·재개·해지).
 *
 * <p>전이별로 메서드를 쪼개지 않고 목표 상태를 값으로 받는다 — 허용 전이의 정본은
 * {@link CardStatus#canTransitionTo}(도메인) 하나뿐이어야 하고, 유스케이스가 전이를
 * 메서드 이름으로 다시 열거하면 그 표가 두 곳에 생긴다.
 */
public interface ChangeCardStatusUseCase {

    Card change(ChangeCardStatusCommand command);

    /**
     * @param reason 필수 — 카드 정지·해지는 감사 대상이라 근거 없는 상태 변경을 남기지 않는다
     * @param requesterUserId 요청자 — 반드시 JWT 주체에서 파생한다(요청 본문 금지, IDOR 방어)
     */
    record ChangeCardStatusCommand(Long cardId, CardStatus targetStatus, String reason,
                                   Long requesterUserId) {
    }
}
