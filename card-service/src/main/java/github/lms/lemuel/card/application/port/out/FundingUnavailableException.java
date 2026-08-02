package github.lms.lemuel.card.application.port.out;

/**
 * 셀러 재원을 확인하지 못했음을 나타낸다 — 재시도 소진·4xx·응답 불량 전부 포함.
 *
 * <p>Task 9 의 {@code CardExceptionHandler} 가 {@code ErrorCode.CARD_FUNDING_UNAVAILABLE} →
 * <b>503</b> 으로 번역한다. 400/422 가 아닌 이유는 신청이 잘못된 게 아니라 우리가 지금 판단할 수
 * 없기 때문이다 — 재시도하면 성공할 수 있는 요청임을 호출자에게 정확히 알린다.
 */
public class FundingUnavailableException extends RuntimeException {

    public FundingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public FundingUnavailableException(String message) {
        super(message);
    }
}
