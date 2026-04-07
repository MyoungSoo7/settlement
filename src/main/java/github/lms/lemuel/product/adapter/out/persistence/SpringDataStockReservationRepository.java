package github.lms.lemuel.product.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SpringDataStockReservationRepository extends JpaRepository<StockReservationJpaEntity, Long> {

    List<StockReservationJpaEntity> findByProductIdAndStatus(Long productId, String status);

    @Query("SELECT sr FROM StockReservationJpaEntity sr WHERE sr.status = 'RESERVED' AND sr.expiresAt < CURRENT_TIMESTAMP")
    List<StockReservationJpaEntity> findExpiredReservations();

    Optional<StockReservationJpaEntity> findByUserIdAndProductIdAndStatus(Long userId, Long productId, String status);
}
