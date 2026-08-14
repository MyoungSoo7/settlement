package github.lms.lemuel.tax.domain.exception;

/**
 * 세금계산서 스캔의 상태 전이 위반 — 종결 상태(MATCHED/REJECTED) 번복이나 정의되지 않은 전이 시도.
 */
public class TaxInvoiceScanStateException extends TaxDomainException {

    public TaxInvoiceScanStateException(String message) {
        super(message);
    }
}
