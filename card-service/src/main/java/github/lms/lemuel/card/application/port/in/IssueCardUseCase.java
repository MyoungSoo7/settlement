package github.lms.lemuel.card.application.port.in;

import github.lms.lemuel.card.domain.Card;

import java.math.BigDecimal;

/**
 * 임직원 카드 발급 유스케이스.
 *
 * <p>{@code holderUserId}(발급 대상)와 {@code requesterUserId}(요청자)가 분리된 것이 핵심이다 —
 * 요청자는 JWT 주체에서만 나오고(본문으로 받지 않는다), 대상은 본문으로 받되 조직 멤버십
 * 프로젝션으로 검증한다. 이 둘을 한 값으로 합치면 "남의 이름으로 발급" 또는
 * "본인 권한 상승" 중 하나가 반드시 열린다.
 */
public interface IssueCardUseCase {

    Card issue(IssueCardCommand command);

    record IssueCardCommand(Long cardAccountId, Long holderUserId, BigDecimal subLimit, Long requesterUserId) {
    }
}
