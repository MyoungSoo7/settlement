package github.lms.lemuel.reservation.application.port.out;

import github.lms.lemuel.reservation.domain.Reservation;

import java.util.List;
import java.util.Optional;

public interface LoadReservationPort {

    Optional<Reservation> findById(Long reservationId);

    List<Reservation> findByCompanyId(Long companyId);

    List<Reservation> findByTechnicianId(Long technicianId);
}
