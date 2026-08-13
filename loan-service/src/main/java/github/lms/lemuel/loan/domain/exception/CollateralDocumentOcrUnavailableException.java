package github.lms.lemuel.loan.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 담보서류 OCR 판독 실패·미구성 — 무폴백(ADR 0036): 부분 결과·추정 판독을 만들지 않고 503 으로 끊는다
 * ({@code LOAN_COLLATERAL_DOC_OCR_UNAVAILABLE}). 재원 조회 실패(503 무폴백)와 같은 논리 — 추정 값으로
 * 여신 근거를 만들지 않는다.
 */
public class CollateralDocumentOcrUnavailableException extends LoanDomainException {

    public CollateralDocumentOcrUnavailableException(String message) {
        super(ErrorCode.LOAN_COLLATERAL_DOC_OCR_UNAVAILABLE, message);
    }
}
