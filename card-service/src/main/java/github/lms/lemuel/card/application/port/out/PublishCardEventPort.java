package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardAccount;

/**
 * 카드 도메인 이벤트 발행 포트 — 구현은 Transactional Outbox 기록이라 도메인 트랜잭션과
 * 같은 트랜잭션에서 커밋된다(발행과 상태 변경이 갈라지지 않는다).
 *
 * <p>Task 11~12 의 한도변경·상태변경 이벤트는 각 유스케이스와 함께 이 포트에 추가된다 —
 * 소비자도 테스트도 없는 시그니처를 미리 열어 두지 않는다.
 */
public interface PublishCardEventPort {

    /**
     * 카드계정 개설(심사 통과) — 토픽 {@code lemuel.card.account_opened}.
     * <b>탈락은 발행하지 않는다</b> — 탈락은 조직 내부 사실이고, 외부 소비자가 반응할 상태 변화가 없다.
     */
    void publishAccountOpened(CardAccount account);

    /**
     * 임직원 카드 발급 — 토픽 {@code lemuel.card.issued}.
     *
     * <p>카드계정을 함께 받는 이유는 {@code organizationId} 때문이다 — 조직 식별자는 카드가 아니라
     * 카드계정에만 있는데, 소비자(감사·알림)는 조직 단위로 반응한다. 발급 유스케이스가 이미
     * 잠가 둔 계정을 그대로 넘기면 추가 조회 없이 실릴 수 있다.
     */
    void publishIssued(Card card, CardAccount account);
}
