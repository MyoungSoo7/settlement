package github.lms.lemuel.insurance.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface SpringDataCommissionClosingRepository
        extends JpaRepository<CommissionClosingJpaEntity, Long> {

    boolean existsByFcIdAndClosingMonth(String fcId, LocalDate closingMonth);

    List<CommissionClosingJpaEntity> findByClosingMonth(LocalDate closingMonth);
}
