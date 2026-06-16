package github.lms.lemuel.reservation.domain.exception;

public class ReservationNotFoundException extends RuntimeException {

    public ReservationNotFoundException(Long reservationId) {
        super("Reservation not found: " + reservationId);
    }
}
