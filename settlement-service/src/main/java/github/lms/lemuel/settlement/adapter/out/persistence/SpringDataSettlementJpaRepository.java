package github.lms.lemuel.settlement.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA Repository for Settlement
 */
public interface SpringDataSettlementJpaRepository extends JpaRepository<SettlementJpaEntity, Long> {

    Optional<SettlementJpaEntity> findByPaymentId(Long paymentId);

    List<SettlementJpaEntity> findBySettlementDate(LocalDate settlementDate);

    List<SettlementJpaEntity> findBySettlementDateAndStatus(LocalDate settlementDate, String status);
}
