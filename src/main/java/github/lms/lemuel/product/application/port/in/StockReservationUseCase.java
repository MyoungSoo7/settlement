package github.lms.lemuel.product.application.port.in;

import github.lms.lemuel.product.domain.StockReservation;

import java.util.List;

public interface StockReservationUseCase {

    StockReservation reserve(Long productId, Long userId, int quantity);

    StockReservation confirm(Long reservationId, Long orderId);

    void release(Long reservationId);

    void releaseExpiredReservations();

    List<StockReservation> getActiveReservations(Long productId);
}
