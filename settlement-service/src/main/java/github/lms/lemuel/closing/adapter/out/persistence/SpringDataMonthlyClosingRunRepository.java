package github.lms.lemuel.closing.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataMonthlyClosingRunRepository extends JpaRepository<MonthlyClosingRunJpaEntity, Long> {

    Optional<MonthlyClosingRunJpaEntity> findByPeriodYm(String periodYm);
}
