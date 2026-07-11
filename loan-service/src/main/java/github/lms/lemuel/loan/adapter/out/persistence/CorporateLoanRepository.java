package github.lms.lemuel.loan.adapter.out.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CorporateLoanRepository extends JpaRepository<CorporateLoanJpaEntity, Long> {

    List<CorporateLoanJpaEntity> findByStockCodeOrderByIdDesc(String stockCode);

    List<CorporateLoanJpaEntity> findAllByOrderByIdDesc(Pageable pageable);
}
