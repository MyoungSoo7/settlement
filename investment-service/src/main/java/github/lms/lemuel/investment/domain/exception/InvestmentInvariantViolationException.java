package github.lms.lemuel.investment.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 투자 도메인 불변식 위반 — 투자 주문/재원/점수/매매계획 산정의 입력·값 규칙을 어겼다
 * (예: 필수값 누락, 종목코드 형식, 금액/예산 부적격, 재무제표 부재).
 *
 * <p>기존 {@code IllegalArgumentException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 */
public class InvestmentInvariantViolationException extends InvestmentDomainException {

    public InvestmentInvariantViolationException(String message) {
        super(ErrorCode.INVALID_ARGUMENT, message);
    }
}
