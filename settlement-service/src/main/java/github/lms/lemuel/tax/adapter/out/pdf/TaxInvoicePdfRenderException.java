package github.lms.lemuel.tax.adapter.out.pdf;

/** 세금계산서 PDF 렌더링 실패. */
public class TaxInvoicePdfRenderException extends RuntimeException {

    public TaxInvoicePdfRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}
