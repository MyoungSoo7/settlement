package github.lms.lemuel.reservation.application.port.out;

import github.lms.lemuel.reservation.domain.Reservation;

public interface SaveReservationPort {

    Reservation save(Reservation reservation);
}
