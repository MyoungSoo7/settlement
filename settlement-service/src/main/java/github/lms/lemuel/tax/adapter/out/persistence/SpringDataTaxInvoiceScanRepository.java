package github.lms.lemuel.tax.adapter.out.persistence;

import github.lms.lemuel.tax.domain.scan.TaxInvoiceScanStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataTaxInvoiceScanRepository extends JpaRepository<TaxInvoiceScanJpaEntity, Long> {

    Optional<TaxInvoiceScanJpaEntity> findBySellerIdAndFileHash(Long sellerId, String fileHash);

    List<TaxInvoiceScanJpaEntity> findByStatusOrderByIdDesc(TaxInvoiceScanStatus status, Pageable pageable);
}
