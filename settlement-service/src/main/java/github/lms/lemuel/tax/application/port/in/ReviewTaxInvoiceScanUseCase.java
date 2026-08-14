package github.lms.lemuel.tax.application.port.in;

import github.lms.lemuel.tax.domain.scan.TaxInvoiceScan;

/**
 * 관리자 리뷰 — OCR 이 사람 판단을 대체하지 않는 지점.
 */
public interface ReviewTaxInvoiceScanUseCase {

    /** 반려(종결) — 저신뢰·위조 의심 등. */
    TaxInvoiceScan reject(Long scanId, String note);

    /** 재대사 — 발행이 뒤늦게 생겼거나 대사 조건이 바뀐 경우. */
    TaxInvoiceScan rematch(Long scanId);
}
