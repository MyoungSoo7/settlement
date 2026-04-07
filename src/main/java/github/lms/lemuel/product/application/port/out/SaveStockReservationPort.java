package github.lms.lemuel.product.application.port.out;

import github.lms.lemuel.product.domain.StockReservation;

public interface SaveStockReservationPort {

    StockReservation save(StockReservation reservation);
}
