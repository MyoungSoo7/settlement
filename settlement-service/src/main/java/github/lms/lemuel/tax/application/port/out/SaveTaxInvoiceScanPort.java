package github.lms.lemuel.tax.application.port.out;

import github.lms.lemuel.tax.domain.scan.TaxInvoiceScan;

public interface SaveTaxInvoiceScanPort {

    TaxInvoiceScan save(TaxInvoiceScan scan);
}
