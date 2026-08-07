package github.lms.lemuel.insurance.application.port.in;

import java.time.LocalDate;

/**
 * 환수(clawback) 스윕 배치 유스케이스 — D6.
 *
 * <p>terminal 상태(SURRENDERED·CANCELLED·EXPIRED)로 종료된 계약을 스캔해:
 * <ul>
 *   <li>미지급(SCHEDULED) 회차 → CANCELLED 소멸 (더 지급될 일 없음)</li>
 *   <li>기지급(PAID) 회차 → 환수액이 0 보다 크면 CLAWBACK_PENDING 전환 +
 *       {@code lemuel.insurance.commission_clawback_triggered} 발행</li>
 * </ul>
 *
 * <p>환수액 산정은 {@code ClawbackCalculator}(D6 공식) — 환수 창구({@code CLAWBACK_WINDOW_MONTHS}) 경과 계약은
 * 환수액 0 이므로 기지급 회차를 PAID 로 남겨 둔다(확정 지급).
 */
public interface SweepCommissionClawbacksUseCase {

    ClawbackSweepResult sweepOn(LocalDate today);

    /**
     * @param cancelledInstallments 소멸된 미지급 회차 수 (SCHEDULED → CANCELLED)
     * @param flaggedPolicies       환수가 트리거된 계약 수
     * @param flaggedInstallments   환수 대기로 전환된 기지급 회차 수 (PAID → CLAWBACK_PENDING)
     */
    record ClawbackSweepResult(int cancelledInstallments, int flaggedPolicies, int flaggedInstallments) {
    }
}
