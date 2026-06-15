package github.lms.lemuel.reservation.application.service;

import github.lms.lemuel.reservation.application.port.in.ChangeReservationStatusUseCase;
import github.lms.lemuel.reservation.application.port.out.LoadReservationPort;
import github.lms.lemuel.reservation.application.port.out.SaveReservationPort;
import github.lms.lemuel.reservation.domain.Reservation;
import github.lms.lemuel.reservation.application.port.out.ReservationTechnicianPort;
import github.lms.lemuel.reservation.domain.exception.ReservationNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Consumer;

/**
 * 시공 예약 상태 전이 서비스.
 *
 * 도메인의 상태 전이 메서드를 호출하고 저장한다. 전이 가드(잘못된 순서 차단)는
 * 도메인에서 IllegalStateException 으로 던지며, 그대로 전파한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ChangeReservationStatusService implements ChangeReservationStatusUseCase {

    private final LoadReservationPort loadReservationPort;
    private final SaveReservationPort saveReservationPort;
    private final ReservationTechnicianPort technicianPort;

    @Override
    public Reservation confirm(Long reservationId) {
        return transition(reservationId, Reservation::confirm, "confirm");
    }

    @Override
    public Reservation assign(Long reservationId, Long technicianId) {
        verifyAssignableTechnician(technicianId);
        Reservation result = transition(reservationId, r -> r.assign(technicianId), "assign");
        log.info("기사 배정: reservationId={}, technicianId={}", reservationId, technicianId);
        return result;
    }

    @Override
    public Reservation reassign(Long reservationId, Long newTechnicianId) {
        verifyAssignableTechnician(newTechnicianId);
        Reservation result = transition(reservationId, r -> r.reassign(newTechnicianId), "reassign");
        log.info("기사 재배정: reservationId={}, newTechnicianId={}", reservationId, newTechnicianId);
        return result;
    }

    /** 배정 대상이 존재하는 APPROVED 상태의 TECHNICIAN 인지 검증한다. (포트로 위임 — 기사 자격은 user 도메인 소유) */
    private void verifyAssignableTechnician(Long technicianId) {
        if (technicianId == null) {
            throw new IllegalArgumentException("technicianId is required");
        }
        if (!technicianPort.isAssignableTechnician(technicianId)) {
            throw new IllegalArgumentException("Assignee must be an active(APPROVED) TECHNICIAN: userId=" + technicianId);
        }
    }

    @Override
    public Reservation start(Long reservationId) {
        return transition(reservationId, Reservation::start, "start");
    }

    @Override
    public Reservation complete(Long reservationId) {
        return transition(reservationId, Reservation::complete, "complete");
    }

    @Override
    public Reservation cancel(Long reservationId, String reason) {
        return transition(reservationId, r -> r.cancel(reason), "cancel");
    }

    private Reservation transition(Long reservationId, Consumer<Reservation> action, String name) {
        Reservation reservation = loadReservationPort.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        action.accept(reservation);
        Reservation saved = saveReservationPort.save(reservation);
        log.info("예약 상태 전이: reservationId={}, action={}, status={}",
                reservationId, name, saved.getStatus());
        return saved;
    }
}
