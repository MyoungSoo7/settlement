package github.lms.lemuel.settlement.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface SpringDataSettlementAdjustmentJpaRepository
        extends JpaRepository<SettlementAdjustmentJpaEntity, Long> {

    List<SettlementAdjustmentJpaEntity> findByAdjustmentDateAndStatus(LocalDate date, String status);
}
