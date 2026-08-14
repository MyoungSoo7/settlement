package github.lms.lemuel.tax.application.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/** 스캔 식별자에 해당하는 행이 없다 — 404. */
public class TaxInvoiceScanNotFoundException extends BusinessException {

    public TaxInvoiceScanNotFoundException(Long scanId) {
        super(ErrorCode.TAX_INVOICE_SCAN_NOT_FOUND, "세금계산서 스캔을 찾을 수 없습니다: " + scanId);
    }
}
