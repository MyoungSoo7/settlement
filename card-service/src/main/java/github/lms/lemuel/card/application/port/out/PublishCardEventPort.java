package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.CardAccount;

/**
 * 카드 도메인 이벤트 발행 포트 — 구현은 Transactional Outbox 기록이라 도메인 트랜잭션과
 * 같은 트랜잭션에서 커밋된다(발행과 상태 변경이 갈라지지 않는다).
 *
 * <p>Task 10~12 의 발급·한도변경·상태변경 이벤트는 각 유스케이스와 함께 이 포트에 추가된다 —
 * 소비자도 테스트도 없는 시그니처를 미리 열어 두지 않는다.
 */
public interface PublishCardEventPort {

    /**
     * 카드계정 개설(심사 통과) — 토픽 {@code lemuel.card.account_opened}.
     * <b>탈락은 발행하지 않는다</b> — 탈락은 조직 내부 사실이고, 외부 소비자가 반응할 상태 변화가 없다.
     */
    void publishAccountOpened(CardAccount account);
}
