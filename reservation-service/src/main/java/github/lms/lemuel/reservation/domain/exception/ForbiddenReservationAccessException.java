package github.lms.lemuel.reservation.domain.exception;

/**
 * 예약 API 접근 권한 부족(403). 인증은 되었으나 역할/소유권이 맞지 않을 때.
 * user 도메인의 InvalidCredentialsException 의존을 끊기 위한 reservation 자체 예외.
 */
public class ForbiddenReservationAccessException extends RuntimeException {

    public ForbiddenReservationAccessException(String message) {
        super(message);
    }
}
