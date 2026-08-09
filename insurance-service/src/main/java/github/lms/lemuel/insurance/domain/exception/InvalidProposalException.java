package github.lms.lemuel.insurance.domain.exception;

/** 가입설계 도메인 불변식 위반. */
public class InvalidProposalException extends RuntimeException {

    public InvalidProposalException(String message) {
        super(message);
    }
}
