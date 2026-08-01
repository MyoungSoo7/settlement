package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardStatus;

import java.math.BigDecimal;

/**
 * 카드 도메인 이벤트 발행 포트 — 구현은 Transactional Outbox 기록이라 도메인 트랜잭션과
 * 같은 트랜잭션에서 커밋된다(발행과 상태 변경이 갈라지지 않는다).
 *
 * <p>시그니처는 유스케이스가 생길 때 함께 추가한다 — 소비자도 테스트도 없는 시그니처를 미리
 * 열어 두지 않는다. 마스터 한도 변경({@code scope=MASTER})은 아직 호출자가 없어(Task 13
 * 재산정 스케줄러) 여기 없다.
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

    /**
     * 임직원 서브한도 변경 — 토픽 {@code lemuel.card.limit_changed}, {@code scope=SUB}.
     *
     * <p>이전 한도를 별도로 받는 이유는 {@code card} 가 이미 <b>변경된 뒤</b>이기 때문이다.
     * 감사·알림 소비자는 "얼마가 됐나"가 아니라 "얼마에서 얼마로"에 반응한다 —
     * 이후 값만 실으면 소비자가 직전 값을 자체 보관해야 하고, 그 순간 유실·순서 문제가
     * 우리 문제에서 소비자 문제로 넘어갈 뿐 사라지지 않는다.
     */
    void publishSubLimitChanged(Card card, CardAccount account, BigDecimal previousSubLimit);

    /**
     * 카드 상태 변경 — 토픽 {@code lemuel.card.status_changed}.
     *
     * <p>호출자는 <b>상태가 실제로 바뀐 경우에만</b> 부른다. {@code suspend()} 는 멱등이라
     * 이미 정지된 카드를 다시 정지해도 변화가 없는데, 그때까지 발행하면 재수신되는 이탈
     * 이벤트(Task 12)마다 소비자가 일어나지 않은 변화를 통지받는다.
     */
    void publishStatusChanged(Card card, CardAccount account, CardStatus previousStatus, String reason);
}
