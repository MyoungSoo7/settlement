package github.lms.lemuel.loan.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 대출 상태머신 위반 — 허용되지 않은 전이({@code from → to})를 시도했다. 선정산 대출(LoanAdvance,
 * {@code LoanStatus})과 기업 신용대출(CorporateLoan, {@code CorporateLoanStatus}) 두 애그리거트가 공유한다.
 *
 * <p>기존 {@code IllegalStateException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 * 전이의 출발/목표 상태를 {@link #getFrom()}·{@link #getTo()} 로 구조적으로 보존한다.
 */
public class InvalidLoanStateException extends LoanDomainException {

    private final transient Enum<?> from;
    private final transient Enum<?> to;

    public InvalidLoanStateException(Enum<?> from, Enum<?> to) {
        super(ErrorCode.INVALID_STATE, "대출 상태 전이 불가: " + from + " → " + to);
        this.from = from;
        this.to = to;
    }

    public Enum<?> getFrom() {
        return from;
    }

    public Enum<?> getTo() {
        return to;
    }
}
