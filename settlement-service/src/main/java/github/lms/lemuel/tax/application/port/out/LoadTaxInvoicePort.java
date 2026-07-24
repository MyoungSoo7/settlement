package github.lms.lemuel.tax.application.port.out;

import github.lms.lemuel.tax.domain.TaxInvoice;

import java.util.Optional;

public interface LoadTaxInvoicePort {

    Optional<TaxInvoice> findBySettlementId(Long settlementId);
}
