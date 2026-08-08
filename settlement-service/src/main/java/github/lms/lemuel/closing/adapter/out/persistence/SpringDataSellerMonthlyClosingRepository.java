package github.lms.lemuel.closing.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataSellerMonthlyClosingRepository
        extends JpaRepository<SellerMonthlyClosingJpaEntity, Long> {

    List<SellerMonthlyClosingJpaEntity> findByPeriodYmOrderBySellerIdAsc(String periodYm);

    void deleteByPeriodYm(String periodYm);
}
