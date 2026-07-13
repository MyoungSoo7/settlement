package github.lms.lemuel.investment.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;
import github.lms.lemuel.investment.domain.InvestmentOrderStatus;

/**
 * 투자 주문 상태머신 위반 — 허용되지 않은 전이({@code from → to})를 시도했다.
 *
 * <p>기존 {@code IllegalStateException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 * 전이의 출발/목표 상태를 {@link #getFrom()}·{@link #getTo()} 로 구조적으로 보존한다.
 */
public class InvalidInvestmentOrderStateException extends InvestmentDomainException {

    private final InvestmentOrderStatus from;
    private final InvestmentOrderStatus to;

    public InvalidInvestmentOrderStateException(InvestmentOrderStatus from, InvestmentOrderStatus to) {
        super(ErrorCode.INVALID_STATE, "투자 주문 상태 전이 불가: " + from + " → " + to);
        this.from = from;
        this.to = to;
    }

    public InvestmentOrderStatus getFrom() {
        return from;
    }

    public InvestmentOrderStatus getTo() {
        return to;
    }
}
