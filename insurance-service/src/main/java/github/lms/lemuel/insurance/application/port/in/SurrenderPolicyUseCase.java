package github.lms.lemuel.insurance.application.port.in;

/**
 * 계약 임의해지 유스케이스 — D7 전이 4(ACTIVE→SURRENDERED) + 해약환급금 산출·지급 생성 (§14).
 *
 * <p><b>돈 경로 원자성</b>: 전이 + 상태변경 이벤트 + payout 생성 + payout_requested 이벤트가
 * 전부 한 tx — 해지만 되고 환급금이 없는 반쪽 해지는 존재하지 않는다.
 */
public interface SurrenderPolicyUseCase {

    /**
     * @throws github.lms.lemuel.insurance.domain.exception.PolicyNotFoundException 계약 없음 (404)
     * @throws github.lms.lemuel.insurance.domain.exception.PolicyOwnershipException 담당 FC 불일치 (403)
     * @throws github.lms.lemuel.insurance.domain.exception.InvalidPolicyTransitionException ACTIVE 아님 (409)
     */
    PolicyTerminationResult surrender(SurrenderPolicyCommand command);

    /** @param fcId 담당 FC 대조용 — §13 과 동일 한계(요청 입력, 실수 방지 수준) */
    record SurrenderPolicyCommand(String policyNumber, String fcId) {
    }
}
