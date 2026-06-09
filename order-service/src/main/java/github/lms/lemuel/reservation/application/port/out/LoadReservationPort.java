package github.lms.lemuel.reservation.application.port.out;

import github.lms.lemuel.reservation.domain.Reservation;
import github.lms.lemuel.reservation.domain.ReservationStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LoadReservationPort {

    Optional<Reservation> findById(Long reservationId);

    List<Reservation> findByCompanyId(Long companyId);

    List<Reservation> findByTechnicianId(Long technicianId);

    /** 관리자 대시보드: 시공일자/상태 필터 조회 (둘 다 null 이면 전체). */
    List<Reservation> search(LocalDate scheduledDate, ReservationStatus status);
}
