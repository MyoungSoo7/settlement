package github.lms.lemuel.company.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CompanySellerLinkRepository extends JpaRepository<CompanySellerLinkJpaEntity, Long> {

    List<CompanySellerLinkJpaEntity> findByStockCode(String stockCode);
}
