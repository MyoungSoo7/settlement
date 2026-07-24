package github.lms.lemuel.tax.application.port.in;

import github.lms.lemuel.tax.domain.TaxInvoice;

import java.util.Optional;

public interface GetTaxInvoiceUseCase {

    Optional<TaxInvoice> bySettlementId(Long settlementId);

    /** 세금계산서 PDF 바이트(가능 시 PDF/A). 미발행이면 빈 Optional. */
    Optional<byte[]> renderPdf(Long settlementId);
}
