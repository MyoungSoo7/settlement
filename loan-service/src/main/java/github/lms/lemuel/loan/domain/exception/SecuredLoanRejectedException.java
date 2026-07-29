package github.lms.lemuel.loan.domain.exception;

import java.math.BigDecimal;

/**
 * 담보/개인신용 대출 심사 거절(신용 불충분·한도 초과·담보가치 부족 등) — 요청은 형식상 유효하나
 * 도메인 규칙상 처리할 수 없는 경우. HTTP 422(Unprocessable Entity)로 매핑된다.
 *
 * <p>도메인 순수성: JDK RuntimeException 만 상속하며 Spring/HTTP 에 의존하지 않는다
 * ({@link CorporateLoanRejectedException} 과 동형).
 *
 * <p>한도 초과 거절은 신청액·한도를 메시지 문자열에만 묻어두지 않고 구조적으로 보존한다
 * (둘 다 nullable — E등급 등 한도와 무관한 거절은 문자열 생성자를 쓴다).
 */
public class SecuredLoanRejectedException extends RuntimeException {

    private final BigDecimal requested;
    private final BigDecimal limit;

    public SecuredLoanRejectedException(String message) {
        this(message, null, null);
    }

    public SecuredLoanRejectedException(String message, BigDecimal requested, BigDecimal limit) {
        super(message);
        this.requested = requested;
        this.limit = limit;
    }

    /** 신청 원금(한도 초과 거절 케이스). 한도와 무관한 거절이면 {@code null}. */
    public BigDecimal getRequested() {
        return requested;
    }

    /** 승인 가능 한도(한도 초과 거절 케이스). 한도와 무관한 거절이면 {@code null}. */
    public BigDecimal getLimit() {
        return limit;
    }
}
