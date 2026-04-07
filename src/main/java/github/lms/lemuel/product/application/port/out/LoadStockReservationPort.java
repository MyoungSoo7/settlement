package github.lms.lemuel.product.application.port.out;

import github.lms.lemuel.product.domain.StockReservation;

import java.util.List;
import java.util.Optional;

public interface LoadStockReservationPort {

    Optional<StockReservation> findById(Long id);

    List<StockReservation> findActiveByProductId(Long productId);

    List<StockReservation> findExpiredReservations();

    Optional<StockReservation> findByUserIdAndProductId(Long userId, Long productId);
}
