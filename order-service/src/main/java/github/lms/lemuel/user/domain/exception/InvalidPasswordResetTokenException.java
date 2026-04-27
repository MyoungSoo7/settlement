package github.lms.lemuel.user.domain.exception;

public class InvalidPasswordResetTokenException extends RuntimeException {

    public InvalidPasswordResetTokenException(String message) {
        super(message);
    }

    public InvalidPasswordResetTokenException() {
        super("유효하지 않거나 만료된 비밀번호 재설정 토큰입니다.");
    }
}
