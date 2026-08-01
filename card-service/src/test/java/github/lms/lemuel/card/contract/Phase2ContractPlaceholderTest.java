package github.lms.lemuel.card.contract;

import github.lms.lemuel.common.events.contract.EventContractValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2단계(승인·매입) 이벤트 계약의 선확정 테스트 — <b>발행 코드보다 계약이 먼저다</b>.
 *
 * <p>1단계에는 이 이벤트를 내는 코드가 없다. 그런데도 지금 스키마를 못 박는 이유는 하위호환
 * 규칙(ADR 0022) 때문이다: 나중에 required 필드를 추가하거나 타입을 바꾸면 그건 파괴적 변경이라
 * <b>신규 토픽 버전</b>을 파야 하고, 이미 붙은 소비자를 전부 이주시켜야 한다. 소비자가 0인 지금이
 * 그 결정을 공짜로 할 수 있는 유일한 시점이다.
 *
 * <p>그래서 이 테스트는 "샘플이 스키마를 통과한다"에서 멈추지 않는다. 필수 필드를 하나 빼거나
 * 금액을 숫자로 바꿨을 때 <b>실제로 위반이 잡히는지</b>까지 확인한다 — 스키마가 required 를
 * 빠뜨린 채로 있으면 통과 테스트는 초록불인 채 계약이 아무것도 지키지 않는다.
 */
class Phase2ContractPlaceholderTest {

    @Test
    @DisplayName("2단계 계약의 정본 샘플이 스키마를 통과한다 — 발행 코드보다 계약이 먼저다")
    void phase2SamplesSatisfySchemas() {
        EventContractValidator.assertValid("lemuel.card.authorized",
                EventContractValidator.canonicalSample("lemuel.card.authorized"));
        EventContractValidator.assertValid("lemuel.card.captured",
                EventContractValidator.canonicalSample("lemuel.card.captured"));
    }

    /**
     * 승인 이벤트의 멱등 자연키는 {@code authorizationId} 다. 이게 required 가 아니면 재전송·리플레이
     * 때 소비자가 중복 승인을 구분할 방법이 없고, 그 사실은 2단계 구현 중이 아니라 운영에서 드러난다.
     */
    @Test
    @DisplayName("authorized 에서 authorizationId 가 빠지면 계약 위반이다 — 멱등 자연키")
    void authorizedWithoutAuthorizationId_isViolation() {
        Set<String> violations = EventContractValidator.validate("lemuel.card.authorized", """
                {"cardId":9001,"cardAccountId":5001,"holderUserId":888,
                 "amount":"45000","authorizedAt":"2026-08-02T10:15:30Z"}
                """);
        assertThat(violations).isNotEmpty();
    }

    /**
     * 금액은 JSON string 이다(DATA-STANDARD N5). 숫자로 실으면 소비자 언어에 따라 double 로
     * 파싱돼 원 단위가 소리 없이 어긋난다 — 이 계약은 GL 분개까지 흘러가므로 그 오차가 곧 장부 오차다.
     */
    @Test
    @DisplayName("금액을 숫자로 실으면 계약 위반이다 — 금액은 JSON string(N5)")
    void numericAmount_isViolation() {
        Set<String> violations = EventContractValidator.validate("lemuel.card.authorized", """
                {"authorizationId":"AUTH-20260802-0001","cardId":9001,"cardAccountId":5001,
                 "holderUserId":888,"amount":45000,"authorizedAt":"2026-08-02T10:15:30Z"}
                """);
        assertThat(violations).isNotEmpty();
    }

    /**
     * 매입은 반드시 <b>어떤 승인의</b> 매입인지 밝혀야 한다. 승인 없는 매입은 상계할 대상이 없고,
     * 부분 매입·취소를 나중에 붙일 때 연결 고리를 새로 만들 수도 없다(required 추가 = 파괴적 변경).
     */
    @Test
    @DisplayName("captured 에서 authorizationId 가 빠지면 계약 위반이다 — 승인 없는 매입은 없다")
    void capturedWithoutAuthorizationId_isViolation() {
        Set<String> violations = EventContractValidator.validate("lemuel.card.captured", """
                {"captureId":"CAP-20260802-0001","cardId":9001,"cardAccountId":5001,
                 "amount":"45000","capturedAt":"2026-08-02T23:50:00Z"}
                """);
        assertThat(violations).isNotEmpty();
    }

    /**
     * 파티션 키는 1단계 카드 이벤트와 같은 {@code cardAccountId} 다. 계약에 이 필드가 없으면
     * 2단계 구현자가 cardId 로 파티셔닝할 여지가 남고, 그러면 같은 계정의 발급·한도변경·승인이
     * 서로 다른 파티션으로 흩어져 소비자가 순서를 잃는다.
     */
    @Test
    @DisplayName("승인·매입 모두 cardAccountId 가 필수다 — 1단계와 같은 파티션 키")
    void bothRequireCardAccountId() {
        assertThat(EventContractValidator.validate("lemuel.card.authorized", """
                {"authorizationId":"AUTH-1","cardId":9001,"holderUserId":888,
                 "amount":"45000","authorizedAt":"2026-08-02T10:15:30Z"}
                """)).isNotEmpty();
        assertThat(EventContractValidator.validate("lemuel.card.captured", """
                {"captureId":"CAP-1","authorizationId":"AUTH-1","cardId":9001,
                 "amount":"45000","capturedAt":"2026-08-02T23:50:00Z"}
                """)).isNotEmpty();
    }

    /**
     * 전방 호환은 지금도 유효해야 한다 — 2단계 구현이 optional 필드(예: 가맹점 업종)를 더한다고
     * 계약이 깨지면, 계약 선확정이 오히려 구현의 족쇄가 된다.
     */
    @Test
    @DisplayName("optional 필드 추가는 위반이 아니다 — 2단계 구현의 여지를 남긴다")
    void additiveFieldIsAllowed() {
        Set<String> violations = EventContractValidator.validate("lemuel.card.authorized", """
                {"authorizationId":"AUTH-1","cardId":9001,"cardAccountId":5001,"holderUserId":888,
                 "amount":"45000","authorizedAt":"2026-08-02T10:15:30Z","merchantCategory":"5812"}
                """);
        assertThat(violations).isEmpty();
    }
}
