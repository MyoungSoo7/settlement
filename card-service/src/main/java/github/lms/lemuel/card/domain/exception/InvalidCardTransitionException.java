package github.lms.lemuel.card.domain.exception;

/**
 * 카드계정(CardAccount)·카드(Card) 상태머신이 허용하지 않는 전이 시도.
 *
 * <p>도메인은 {@code BusinessException}(shared-common) 을 쓰지 않는다 — ErrorCode 매핑은
 * 응용 계층(Task 9+)의 책임이고, 도메인은 프레임워크·공통모듈 예외 계층에 묶이지 않는 순수
 * {@link RuntimeException} 을 던진다(organization-service 의 InvalidOrganizationTransitionException 과 동형).
 */
public class InvalidCardTransitionException extends RuntimeException {

    public InvalidCardTransitionException(String message) {
        super(message);
    }

    public InvalidCardTransitionException(Enum<?> from, Enum<?> to) {
        super("허용되지 않는 카드 상태 전이: " + from + " → " + to);
    }
}
