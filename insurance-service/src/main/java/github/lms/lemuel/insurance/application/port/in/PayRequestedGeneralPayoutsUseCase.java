package github.lms.lemuel.insurance.application.port.in;

import java.time.LocalDate;

/**
 * 일반지급 실행 배치 유스케이스 (§14) — REQUESTED 전건을 지급(PAID)한다.
 *
 * <p>due 개념이 없다 — payout 은 이미 확정된 지급 의무라 다음 배치가 전건 지급한다.
 * 이중 지급은 도메인 전이 가드(REQUESTED→PAID 만 허용)가 차단한다.
 */
public interface PayRequestedGeneralPayoutsUseCase {

    GeneralPayoutBatchResult payRequestedOn(LocalDate today);

    /** @param paid 지급 완료된 일반지급 건수 (REQUESTED → PAID) */
    record GeneralPayoutBatchResult(int paid) {
    }
}
