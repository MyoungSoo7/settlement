package github.lms.lemuel.tax.application.port.out;

import github.lms.lemuel.tax.domain.TaxInvoice;

public interface SaveTaxInvoicePort {

    TaxInvoice save(TaxInvoice invoice);
}
