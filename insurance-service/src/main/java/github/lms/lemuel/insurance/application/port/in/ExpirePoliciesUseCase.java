package github.lms.lemuel.insurance.application.port.in;

import java.time.LocalDate;

/**
 * 만기·실효소멸 판정 배치 유스케이스.
 *
 * <p>D7 전이 2개를 일 배치로 자동 집행한다:
 * <ul>
 *   <li>전이 5 — ACTIVE → EXPIRED : 만기일 도래</li>
 *   <li>전이 3 — LAPSED → EXPIRED : 실효일로부터 부활 창구 도과</li>
 * </ul>
 */
public interface ExpirePoliciesUseCase {

    /**
     * {@code today} 기준으로 만기·실효소멸 판정을 일괄 집행한다.
     * 전이 1건당 {@code lemuel.insurance.policy_status_changed} 가 Outbox 로 발행된다.
     */
    ExpiryBatchResult expireOn(LocalDate today);

    /**
     * @param maturedExpired 만기 도래로 소멸된 계약 수 (ACTIVE → EXPIRED)
     * @param lapsedExpired  부활 창구 도과로 소멸된 계약 수 (LAPSED → EXPIRED)
     */
    record ExpiryBatchResult(int maturedExpired, int lapsedExpired) {

        public int total() {
            return maturedExpired + lapsedExpired;
        }
    }
}
