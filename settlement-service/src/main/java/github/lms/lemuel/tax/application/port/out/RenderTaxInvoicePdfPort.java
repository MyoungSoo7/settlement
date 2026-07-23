package github.lms.lemuel.tax.application.port.out;

import github.lms.lemuel.tax.domain.TaxInvoice;

/**
 * 세금계산서 PDF 렌더링 포트 — 어댑터(iText + GhostscriptService PDF/A 아카이빙)가 구현한다.
 */
public interface RenderTaxInvoicePdfPort {

    /** 세금계산서 1건을 PDF(가능 시 PDF/A) 바이트로 렌더링한다. */
    byte[] render(TaxInvoice invoice);
}
