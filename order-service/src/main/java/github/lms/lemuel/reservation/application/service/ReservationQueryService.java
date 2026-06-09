package github.lms.lemuel.reservation.application.service;

import github.lms.lemuel.reservation.application.port.in.GetReservationUseCase;
import github.lms.lemuel.reservation.application.port.out.LoadReservationPort;
import github.lms.lemuel.reservation.domain.Reservation;
import github.lms.lemuel.reservation.domain.exception.ReservationNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationQueryService implements GetReservationUseCase {

    private final LoadReservationPort loadReservationPort;

    @Override
    public Reservation getById(Long reservationId) {
        return loadReservationPort.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
    }

    @Override
    public List<Reservation> getByCompany(Long companyId) {
        return loadReservationPort.findByCompanyId(companyId);
    }
}
