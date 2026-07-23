package github.lms.lemuel.tax.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataTaxInvoiceRepository extends JpaRepository<TaxInvoiceJpaEntity, Long> {

    Optional<TaxInvoiceJpaEntity> findBySettlementId(Long settlementId);
}
