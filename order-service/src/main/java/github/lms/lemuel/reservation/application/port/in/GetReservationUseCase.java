package github.lms.lemuel.reservation.application.port.in;

import github.lms.lemuel.reservation.domain.Reservation;
import github.lms.lemuel.reservation.domain.ReservationStatus;

import java.time.LocalDate;
import java.util.List;

public interface GetReservationUseCase {

    Reservation getById(Long reservationId);

    List<Reservation> getByCompany(Long companyId);

    List<Reservation> getByTechnician(Long technicianId);

    /** 관리자 대시보드: 시공일자/상태 필터 조회 (둘 다 선택). */
    List<Reservation> search(LocalDate scheduledDate, ReservationStatus status);
}
