package github.lms.lemuel.loan.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

import github.lms.lemuel.loan.domain.CollateralDocumentStatus;

/**
 * 담보서류 대사 게이트 위반 — 첨부된 서류가 MATCHED 가 아닌데 승인을 시도했다.
 * 422 ({@code LOAN_COLLATERAL_DOC_NOT_MATCHED}) — 심사 거절(422)과 같은 결의 "지금은 승인 불가".
 */
public class CollateralDocumentNotMatchedException extends LoanDomainException {

    public CollateralDocumentNotMatchedException(Long loanId, CollateralDocumentStatus status, String note) {
        super(ErrorCode.LOAN_COLLATERAL_DOC_NOT_MATCHED,
                "담보서류 대사 미통과(" + status + ")로 승인할 수 없습니다: loanId=" + loanId
                        + (note == null ? "" : " — " + note));
    }

    /** 전면 강제(required=true)에서 서류 미첨부 담보대출 승인 시도 (무담보 상품은 대상 아님). */
    public static CollateralDocumentNotMatchedException missing(Long loanId) {
        return new CollateralDocumentNotMatchedException(
                "담보서류가 첨부되지 않아 승인할 수 없습니다(전면 강제): loanId=" + loanId);
    }

    private CollateralDocumentNotMatchedException(String message) {
        super(ErrorCode.LOAN_COLLATERAL_DOC_NOT_MATCHED, message);
    }
}
