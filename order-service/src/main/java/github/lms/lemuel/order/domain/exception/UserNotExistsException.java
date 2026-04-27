package github.lms.lemuel.order.domain.exception;

public class UserNotExistsException extends RuntimeException {
    
    public UserNotExistsException(Long userId) {
        super("존재하지 않는 사용자입니다: " + userId);
    }
    
    public UserNotExistsException(Long userId, Throwable cause) {
        super("존재하지 않는 사용자입니다: " + userId, cause);
    }
}
