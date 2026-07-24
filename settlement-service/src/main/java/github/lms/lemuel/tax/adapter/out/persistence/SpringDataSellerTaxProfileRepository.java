package github.lms.lemuel.tax.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataSellerTaxProfileRepository extends JpaRepository<SellerTaxProfileJpaEntity, Long> {
}
