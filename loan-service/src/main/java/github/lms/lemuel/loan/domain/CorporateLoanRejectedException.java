package github.lms.lemuel.loan.domain;

/**
 * 기업대출 심사 거절(신용 불충분·한도 초과·재무자료 없음 등) — 요청은 형식상 유효하나 도메인 규칙상
 * 처리할 수 없는 경우. HTTP 422(Unprocessable Entity)로 매핑된다(CorporateLoanExceptionHandler).
 *
 * <p>도메인 순수성: JDK RuntimeException 만 상속하며 Spring/HTTP 에 의존하지 않는다.
 */
public class CorporateLoanRejectedException extends RuntimeException {

    public CorporateLoanRejectedException(String message) {
        super(message);
    }
}
