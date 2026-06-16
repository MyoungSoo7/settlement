package github.lms.lemuel.reservation.adapter.in.web;

import github.lms.lemuel.reservation.domain.exception.ForbiddenReservationAccessException;
import github.lms.lemuel.reservation.domain.exception.ReservationNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Reservation 모듈 전용 Exception Handler.
 *
 * 패키지 스코프로 한정해 다른 도메인의 IllegalStateException 매핑에 영향을 주지 않는다.
 * (IllegalArgumentException → 400, UserNotFoundException → 404 등은 전역 핸들러가 처리)
 */
@RestControllerAdvice(basePackageClasses = ReservationController.class)
public class ReservationExceptionHandler {

    /** 404 - 예약을 찾을 수 없음 */
    @ExceptionHandler(ReservationNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(ReservationNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    /** 403 - 권한/소유권 부족 */
    @ExceptionHandler(ForbiddenReservationAccessException.class)
    public ResponseEntity<Map<String, String>> handleForbidden(ForbiddenReservationAccessException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", ex.getMessage()));
    }

    /** 409 - 잘못된 예약 상태 전이 (가드 위반) */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleInvalidTransition(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
    }
}
