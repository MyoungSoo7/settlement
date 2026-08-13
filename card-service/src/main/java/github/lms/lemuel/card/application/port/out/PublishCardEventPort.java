package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.AuthorizationHold;
import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardAccountStatus;
import github.lms.lemuel.card.domain.CardCapture;
import github.lms.lemuel.card.domain.CardStatus;
import github.lms.lemuel.card.domain.LimitChangeResult;

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

    /**
     * 법인 마스터 한도 변경 — 토픽 {@code lemuel.card.limit_changed}, {@code scope=MASTER}.
     * 서브한도 변경과 같은 토픽·같은 파티션 키(cardAccountId)를 쓴다 — 한 계정의 마스터·서브
     * 변경이 순서를 잃으면 소비자가 한도 배분을 뒤집힌 채로 보게 된다.
     *
     * <p>{@code clamped=true} 는 하향이 Σ서브한도 하한에 걸려 <b>요청보다 덜 내려갔음</b>을 뜻한다.
     * 이 값을 싣지 않으면 소비자는 "재산정이 도는데 한도가 안 내려간다"를 버그로 신고하게 된다 —
     * 실제로는 발급된 카드를 조용히 죽이지 않으려는 도메인 규칙이 작동한 것이다.
     */
    void publishMasterLimitChanged(CardAccount account, BigDecimal previousLimit, LimitChangeResult result);

    /**
     * 카드계정 상태 변경 — 토픽 {@code lemuel.card.account_status_changed}.
     *
     * <p>임직원 카드의 {@code status_changed} 와 <b>토픽을 나눈다</b>. 계정과 카드는 상태 enum 도
     * 소비자도 다르다(계정 정지는 법인 여신 사건, 카드 정지는 개인 사건). 한 토픽에 섞으면
     * cardId·holderUserId 가 있는 페이로드와 없는 페이로드를 소비자가 런타임에 분기해야 한다.
     */
    void publishAccountStatusChanged(CardAccount account, CardAccountStatus previousStatus, String reason);

    /**
     * 카드 승인(authorization) — 토픽 {@code lemuel.card.authorized}.
     *
     * <p>파티션 키는 1단계 카드 이벤트와 같은 {@code cardAccountId} 다 — 같은 계정의
     * 발급·한도변경·승인이 같은 파티션에 순서대로 떨어져야 소비자가 상태를 일관되게 본다.
     *
     * <p>금액은 {@code BigDecimal.toPlainString()} 으로 직렬화한다(DATA-STANDARD N5) —
     * 이 이벤트는 GL 분개까지 흘러가므로 float 파싱 오차가 장부 오차가 된다.
     *
     * @param hold    저장된 승인 홀드(authorizationId · amount · authorizedAt 포함)
     * @param card    승인 대상 카드(서브한도 계산에 쓴다)
     * @param account 카드계정(파티션 키 · 잔여 마스터한도 계산에 쓴다)
     */
    void publishAuthorized(AuthorizationHold hold, Card card, CardAccount account);

    /**
     * 카드 매입(capture) — 토픽 {@code lemuel.card.captured}.
     *
     * <p>계약 스키마({@code lemuel.card.captured.schema.json}) required 필드:
     * captureId · authorizationId · cardId · cardAccountId · amount · capturedAt.
     *
     * <p>파티션 키: {@code cardAccountId}(1단계·2단계 승인과 동일) — 같은 계정의 발급·승인·매입이
     * 같은 파티션에 순서대로 떨어져야 소비자(account-service GL 분개)가 일관된 순서를 본다.
     *
     * <p>금액({@code amount})은 승인 금액이 아니라 <b>이번 매입 금액</b>이다(부분 매입에서 둘은 다르다).
     * 반드시 {@link java.math.BigDecimal#toPlainString()} 으로 직렬화한다(DATA-STANDARD N5).
     *
     * @param capture 저장된 매입 레코드(captureId · capturedAmount · capturedAt 포함)
     * @param hold    승인 홀드(authorizationId 포함)
     */
    void publishCaptured(CardCapture capture, AuthorizationHold hold);

    /**
     * 청구서 전액 납부 완료 — 토픽 {@code lemuel.card.statement_paid}.
     *
     * <p>계약 스키마({@code lemuel.card.statement_paid.schema.json}) required 필드:
     * statementId · cardAccountId · billingYearMonth · paidAmount · paymentId · paidAt.
     *
     * <p>파티션 키: {@code cardAccountId} — 1단계 카드 이벤트·2단계 승인·매입과 동일.
     * 같은 계정의 발급·승인·매입·청구 이벤트가 같은 파티션에 순서대로 떨어져야 소비자(GL 분개)가
     * 일관된 순서를 본다.
     *
     * <p>금액({@code paidAmount})은 반드시 {@link java.math.BigDecimal#toPlainString()} 으로
     * 직렬화한다(DATA-STANDARD N5).
     *
     * @param statement 전액 납부된 명세서
     * @param paymentId 이번 납부 멱등 키
     */
    void publishStatementPaid(github.lms.lemuel.card.domain.CardStatement statement, String paymentId);
}
