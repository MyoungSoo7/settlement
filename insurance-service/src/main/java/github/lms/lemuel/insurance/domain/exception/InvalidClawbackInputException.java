package github.lms.lemuel.insurance.domain.exception;

/** 환수 계산의 금액 또는 날짜 불변식을 위반한 입력. */
public class InvalidClawbackInputException extends RuntimeException {

    public InvalidClawbackInputException(String message) {
        super(message);
    }
}
