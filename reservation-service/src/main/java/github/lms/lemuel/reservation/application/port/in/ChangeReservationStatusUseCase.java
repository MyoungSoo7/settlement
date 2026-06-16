package github.lms.lemuel.reservation.application.port.in;

import github.lms.lemuel.reservation.domain.Reservation;

/**
 * 시공 예약 상태 전이 UseCase.
 *
 * 상태머신: REQUESTED → CONFIRMED → ASSIGNED → IN_PROGRESS → COMPLETED ; → CANCELED
 * 각 전이의 유효성(가드)은 도메인({@link Reservation})에서 강제한다.
 */
public interface ChangeReservationStatusUseCase {

    /** REQUESTED → CONFIRMED (관리자 확인) */
    Reservation confirm(Long reservationId);

    /** CONFIRMED → ASSIGNED (시공기사 배정). technicianId 는 실제 APPROVED 상태의 TECHNICIAN 이어야 한다. */
    Reservation assign(Long reservationId, Long technicianId);

    /** ASSIGNED 상태에서 담당 기사를 교체한다(상태 유지). newTechnicianId 도 APPROVED TECHNICIAN 이어야 한다. */
    Reservation reassign(Long reservationId, Long newTechnicianId);

    /** ASSIGNED → IN_PROGRESS (시공 시작) */
    Reservation start(Long reservationId);

    /** IN_PROGRESS → COMPLETED (시공 완료) */
    Reservation complete(Long reservationId);

    /** 비종료 상태 → CANCELED (취소) */
    Reservation cancel(Long reservationId, String reason);
}
