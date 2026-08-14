package github.lms.lemuel.loan.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 담보서류 미존재 — 404 ({@code LOAN_COLLATERAL_DOC_NOT_FOUND}).
 */
public class CollateralDocumentNotFoundException extends LoanDomainException {

    public CollateralDocumentNotFoundException(Long documentId) {
        super(ErrorCode.LOAN_COLLATERAL_DOC_NOT_FOUND,
                "담보서류를 찾을 수 없습니다: documentId=" + documentId);
    }
}
