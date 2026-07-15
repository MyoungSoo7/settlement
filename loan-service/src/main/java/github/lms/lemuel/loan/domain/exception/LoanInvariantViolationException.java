package github.lms.lemuel.loan.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

import java.math.BigDecimal;

/**
 * 대출 도메인 불변식 위반 — 신청/상환/전표/투영의 입력·값 규칙을 어겼다
 * (예: 종목코드 형식, 원금·수수료·상환액 부적격, 신용점수 범위, 전표 금액 양수, 필수값 누락).
 *
 * <p>기존 {@code IllegalArgumentException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 *
 * <p>한도 초과처럼 <em>요청값과 허용 한도</em>를 비교하다 위반한 경우, 두 값을 메시지 문자열에만 묻어두지
 * 않고 {@link #getRequested()}/{@link #getLimit()} 로 구조화 보존한다(둘 다 nullable — 형식/필수값
 * 위반 등 한도와 무관한 케이스는 문자열 생성자를 그대로 쓴다).
 */
public class LoanInvariantViolationException extends LoanDomainException {

    private final BigDecimal requested;
    private final BigDecimal limit;

    public LoanInvariantViolationException(String message) {
        this(message, null, null);
    }

    public LoanInvariantViolationException(String message, BigDecimal requested, BigDecimal limit) {
        super(ErrorCode.INVALID_ARGUMENT, message);
        this.requested = requested;
        this.limit = limit;
    }

    /** 위반을 유발한 요청값(한도 비교 케이스). 한도와 무관한 위반이면 {@code null}. */
    public BigDecimal getRequested() {
        return requested;
    }

    /** 초과된 허용 한도(한도 비교 케이스). 한도와 무관한 위반이면 {@code null}. */
    public BigDecimal getLimit() {
        return limit;
    }
}
