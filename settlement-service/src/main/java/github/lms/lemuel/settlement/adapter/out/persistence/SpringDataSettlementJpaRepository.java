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

    /**
     * 보류 해제 배치 — release_date <= today 이고 아직 released=false 이며 holdback > 0 인 row.
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT s FROM SettlementJpaEntity s " +
            "WHERE s.holdbackReleased = false " +
            "  AND s.holdbackAmount > 0 " +
            "  AND s.holdbackReleaseDate <= :today " +
            "ORDER BY s.holdbackReleaseDate ASC")
    List<SettlementJpaEntity> findReleasableHoldbacks(
            @org.springframework.data.repository.query.Param("today") LocalDate today,
            org.springframework.data.domain.Pageable pageable);
}
