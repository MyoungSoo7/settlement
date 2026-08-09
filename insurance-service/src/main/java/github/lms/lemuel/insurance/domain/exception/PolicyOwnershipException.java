package github.lms.lemuel.insurance.domain.exception;

/**
 * 계약 소유권 불일치 — 담당 FC 가 아닌 주체의 해지·철회 시도 (REST 403).
 *
 * <p>⚠️ §13 과 동일 한계: insurance-service 는 fcId 를 JWT 주체가 아닌 요청 입력으로 받는
 * 전역 관례라 이 대조는 실수 방지 수준이다 — JWT 주체 파생 배선은 서비스 전역 별도 과제.
 */
public class PolicyOwnershipException extends RuntimeException {

    public PolicyOwnershipException(String policyNumber, String requestedFcId) {
        super("계약 담당 FC 가 아닙니다: policyNumber=" + policyNumber + ", fcId=" + requestedFcId);
    }
}
