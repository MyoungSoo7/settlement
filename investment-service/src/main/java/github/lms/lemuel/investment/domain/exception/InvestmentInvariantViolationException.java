package github.lms.lemuel.investment.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 투자 도메인 불변식 위반 — 투자 주문/재원/점수/매매계획 산정의 입력·값 규칙을 어겼다
 * (예: 필수값 누락, 종목코드 형식, 금액/예산 부적격, 재무제표 부재).
 *
 * <p>기존 {@code IllegalArgumentException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 *
 * <p>위반을 유발한 값(음수 금액·형식 오류 종목코드·부적격 예산 등)을 메시지 문자열에만 묻어두지 않고
 * {@link #getViolatingValue()} 로 구조화 보존한다(nullable — 필수값 누락 등 위반값이 없는 케이스는
 * 문자열 생성자를 그대로 쓴다).
 */
public class InvestmentInvariantViolationException extends InvestmentDomainException {

    private final transient Object violatingValue;

    public InvestmentInvariantViolationException(String message) {
        this(message, null);
    }

    public InvestmentInvariantViolationException(String message, Object violatingValue) {
        super(ErrorCode.INVALID_ARGUMENT, message);
        this.violatingValue = violatingValue;
    }

    /** 위반을 유발한 값(예: 음수 금액). 위반값이 없는 케이스(필수값 누락 등)면 {@code null}. */
    public Object getViolatingValue() {
        return violatingValue;
    }
}
