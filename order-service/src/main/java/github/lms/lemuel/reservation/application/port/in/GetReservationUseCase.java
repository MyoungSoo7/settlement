package github.lms.lemuel.reservation.application.port.in;

import github.lms.lemuel.reservation.domain.Reservation;

import java.util.List;

public interface GetReservationUseCase {

    Reservation getById(Long reservationId);

    List<Reservation> getByCompany(Long companyId);

    List<Reservation> getByTechnician(Long technicianId);
}
