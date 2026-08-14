package github.lms.lemuel.insurance.application.port.in;

import github.lms.lemuel.insurance.application.port.in.GetPolicyPayoutsUseCase.GeneralPayoutSummary;
import github.lms.lemuel.insurance.domain.PolicyStatus;

/**
 * 해지·철회 결과 — 전이된 상태 + 생성된 일반지급 요약.
 *
 * @param payout 생성된 일반지급 (환급액 0 으로 payout 이 생성되지 않았으면 {@code null} — D-G3)
 */
public record PolicyTerminationResult(String policyNumber, PolicyStatus status,
                                      GeneralPayoutSummary payout) {
}
