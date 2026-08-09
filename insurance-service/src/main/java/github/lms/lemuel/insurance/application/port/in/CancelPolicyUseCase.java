package github.lms.lemuel.insurance.application.port.in;

/**
 * 청약철회 유스케이스 — D7 전이 6·7(ACTIVE|LAPSED→CANCELLED, 효력일 15일 창구)
 * + 기납입보험료 전액 환급 payout 생성 (§14).
 *
 * <p>철회는 D6 수수료 전액 환수와 대칭 — 회사에 남는 돈이 없다.
 */
public interface CancelPolicyUseCase {

    /**
     * @throws github.lms.lemuel.insurance.domain.exception.PolicyNotFoundException 계약 없음 (404)
     * @throws github.lms.lemuel.insurance.domain.exception.PolicyOwnershipException 담당 FC 불일치 (403)
     * @throws github.lms.lemuel.insurance.domain.exception.InvalidPolicyTransitionException
     *         15일 창구 초과 또는 불허용 상태 (409)
     */
    PolicyTerminationResult cancel(CancelPolicyCommand command);

    /** @param fcId 담당 FC 대조용 — §13 과 동일 한계(요청 입력, 실수 방지 수준) */
    record CancelPolicyCommand(String policyNumber, String fcId) {
    }
}
