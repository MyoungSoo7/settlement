package github.lms.lemuel.card.application.port.in;

import github.lms.lemuel.card.domain.Card;

import java.math.BigDecimal;

/**
 * 임직원 카드의 서브한도 변경.
 *
 * <p>카드계정은 명령에 없다 — 카드가 자기 소속 계정을 알고 있고, 요청자가 계정을 지정할 수
 * 있게 하면 "다른 계정의 카드"를 가리키는 조합이 입력으로 존재하게 된다. 하나로 결정되는 값을
 * 두 번 받지 않는 것이 검증해야 할 경우의 수를 줄이는 방법이다.
 */
public interface ChangeSubLimitUseCase {

    Card change(ChangeSubLimitCommand command);

    /**
     * @param requesterUserId 요청자 — 반드시 JWT 주체에서 파생한다(요청 본문 금지, IDOR 방어)
     */
    record ChangeSubLimitCommand(Long cardId, BigDecimal newSubLimit, Long requesterUserId) {
    }
}
