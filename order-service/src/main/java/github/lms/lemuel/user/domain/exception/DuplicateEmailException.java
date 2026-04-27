package github.lms.lemuel.user.domain.exception;

public class DuplicateEmailException extends RuntimeException {
    
    public DuplicateEmailException(String email) {
        super("이미 존재하는 이메일입니다: " + email);
    }
    
    public DuplicateEmailException(String email, Throwable cause) {
        super("이미 존재하는 이메일입니다: " + email, cause);
    }
}
