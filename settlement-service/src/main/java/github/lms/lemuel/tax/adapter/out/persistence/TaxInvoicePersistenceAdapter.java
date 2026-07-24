package github.lms.lemuel.tax.adapter.out.persistence;

import github.lms.lemuel.tax.application.port.out.LoadTaxInvoicePort;
import github.lms.lemuel.tax.application.port.out.SaveTaxInvoicePort;
import github.lms.lemuel.tax.domain.TaxInvoice;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class TaxInvoicePersistenceAdapter implements LoadTaxInvoicePort, SaveTaxInvoicePort {

    private final SpringDataTaxInvoiceRepository repository;

    public TaxInvoicePersistenceAdapter(SpringDataTaxInvoiceRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<TaxInvoice> findBySettlementId(Long settlementId) {
        if (settlementId == null) {
            return Optional.empty();
        }
        return repository.findBySettlementId(settlementId).map(TaxInvoicePersistenceAdapter::toDomain);
    }

    @Override
    public TaxInvoice save(TaxInvoice invoice) {
        TaxInvoiceJpaEntity entity = new TaxInvoiceJpaEntity(
                invoice.getId(), invoice.getSettlementId(), invoice.getSellerId(),
                invoice.getSupplyAmount(), invoice.getTaxAmount(), invoice.getTotalAmount(),
                invoice.getIssueDate(), invoice.getIssueNumber(), invoice.getCreatedAt());
        return toDomain(repository.saveAndFlush(entity));
    }

    private static TaxInvoice toDomain(TaxInvoiceJpaEntity e) {
        return TaxInvoice.rehydrate(e.getId(), e.getSettlementId(), e.getSellerId(),
                e.getSupplyAmount(), e.getTaxAmount(), e.getTotalAmount(), e.getIssueDate(),
                e.getIssueNumber(), e.getCreatedAt());
    }
}
