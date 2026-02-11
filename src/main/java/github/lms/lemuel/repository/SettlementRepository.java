package github.lms.lemuel.repository;

import github.lms.lemuel.domain.Settlement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface SettlementRepository extends JpaRepository<Settlement, Long> {
    Optional<Settlement> findByPaymentId(Long paymentId);
    List<Settlement> findBySettlementDate(LocalDate settlementDate);
    List<Settlement> findByStatus(Settlement.SettlementStatus status);

    // Spring Batch용 페이징 쿼리
    Page<Settlement> findBySettlementDateAndStatus(
        LocalDate settlementDate,
        Settlement.SettlementStatus status,
        Pageable pageable
    );
}
