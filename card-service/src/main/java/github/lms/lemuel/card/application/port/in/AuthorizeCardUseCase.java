package github.lms.lemuel.card.application.port.in;

import github.lms.lemuel.card.domain.AuthorizationHold;
import github.lms.lemuel.card.domain.DeclineReason;

import java.math.BigDecimal;

/**
 * 카드 실시간 승인 유스케이스 포트.
 *
 * <p>VAN 시뮬레이터({@code AuthorizationVanAdapter})와 내부 API({@code AuthorizationInternalApiAdapter})
 * 두 어댑터가 이 단일 포트를 통해 승인을 요청한다 — 두 어댑터는 형식 변환만 담당하고,
 * 승인 결정 로직은 이 포트 구현체({@code AuthorizeCardService})에만 있다.
 *
 * <p>승인 결과는 도메인 예외가 아닌 {@link AuthorizationResult}로 표현한다 — 거절은
 * 예외적 사건이 아니라 정상 도메인 결과이기 때문이다(예외를 거절에 쓰면 호출자가 흐름 제어를
 * try-catch로 해야 하고, 거절 사유가 스택 트레이스와 섞인다).
 */
public interface AuthorizeCardUseCase {

    AuthorizationResult authorize(AuthorizeCardCommand command);

    /**
     * 승인 요청 커맨드.
     *
     * @param authorizationId 멱등 자연키 — 재전송·리플레이 시 중복 승인을 구분하는 유일한 수단
     * @param cardId          승인 대상 카드 ID
     * @param amount          승인 요청 금액(양수, BigDecimal)
     * @param merchantName    가맹점 이름(optional)
     * @param mcc             4자리 가맹점 업종코드(optional — 코드 없는 가맹점 허용)
     * @param overseas        해외 거래 여부
     * @param online          온라인 거래 여부
     */
    record AuthorizeCardCommand(
            String authorizationId,
            Long cardId,
            BigDecimal amount,
            String merchantName,
            String mcc,
            boolean overseas,
            boolean online
    ) {
    }

    /**
     * 승인 결과.
     *
     * <p>승인 시: {@code approved=true}, {@code hold!=null}, {@code declineReason=null}
     * <p>거절 시: {@code approved=false}, {@code hold=null}, {@code declineReason!=null}
     */
    record AuthorizationResult(
            boolean approved,
            AuthorizationHold hold,
            DeclineReason declineReason
    ) {

        public static AuthorizationResult approved(AuthorizationHold hold) {
            return new AuthorizationResult(true, hold, null);
        }

        public static AuthorizationResult declined(DeclineReason reason) {
            return new AuthorizationResult(false, null, reason);
        }
    }
}
