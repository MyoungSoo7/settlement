package github.lms.lemuel.insurance.domain.exception;

/**
 * 증권번호로 계약을 찾지 못함 — REST 404 로 매핑된다.
 */
public class PolicyNotFoundException extends RuntimeException {

    public PolicyNotFoundException(String policyNumber) {
        super("계약을 찾을 수 없습니다: policyNumber=" + policyNumber);
    }
}
