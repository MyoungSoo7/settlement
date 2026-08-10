package github.lms.lemuel.insurance.domain.exception;

/**
 * 계약 소유권 불일치 — 담당 FC 가 아닌 주체의 해지·철회·지급내역 조회 시도 (REST 403).
 *
 * <p>대조 대상 fcId 는 요청이 아니라 JWT 주체에서 파생된다({@code FcIdentity}) — 요청 본문의
 * fcId 를 신뢰하면 남의 식별자를 아는 것만으로 타인 계약을 해지시킬 수 있다.
 * userId 없는 구(舊) 토큰도 이 예외로 통일한다 — 계약 존재 여부가 응답 차이로 새 나가지 않도록.
 */
public class PolicyOwnershipException extends RuntimeException {

    public PolicyOwnershipException(String policyNumber, String requestedFcId) {
        super("계약 담당 FC 가 아닙니다: policyNumber=" + policyNumber + ", fcId=" + requestedFcId);
    }
}
