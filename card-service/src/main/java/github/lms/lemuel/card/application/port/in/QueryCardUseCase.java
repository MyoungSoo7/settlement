package github.lms.lemuel.card.application.port.in;

import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardAccount;

import java.util.List;

/**
 * 카드 조회 유스케이스.
 *
 * <p>조회에도 인가가 붙는다 — 한도와 보유 현황 자체가 여신·인사 정보다. 다만 등급이 다르다:
 * 계정 요약은 구성원이면 누구나(자기 회사 한도는 알아야 한다), 임직원 카드 목록은 OWNER·MANAGER 만.
 *
 * <p>{@link #listMyCards} 만 조직 인가를 거치지 않는다. 대상이 파라미터가 아니라 <b>주체 자신</b>이라
 * 남의 것을 볼 경로가 애초에 없기 때문이다 — 대상을 입력으로 받는 순간 그게 IDOR 경로가 된다.
 */
public interface QueryCardUseCase {

    CardAccount getAccount(Long cardAccountId, Long requesterUserId);

    List<Card> listCards(Long cardAccountId, Long requesterUserId);

    List<Card> listMyCards(Long requesterUserId);
}
