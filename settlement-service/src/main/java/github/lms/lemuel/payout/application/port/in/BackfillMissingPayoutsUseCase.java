package github.lms.lemuel.payout.application.port.in;

import github.lms.lemuel.payout.domain.PayoutBackfillReport;

import java.time.LocalDate;

/**
 * 미생성 Payout 멱등 백필 유스케이스.
 *
 * <p>INV-6(settlementsWithoutPayout) 탐지 결과를 근거로, 확정(DONE) 됐지만 Payout 이 없는 과거 정산에
 * 대해 지급유형별(IMMEDIATE·HOLDBACK_RELEASE) Payout 을 멱등·append-only 로 신규 생성한다.
 *
 * <ul>
 *   <li>멱등성: (settlement_id, payout_type) UNIQUE 제약을 이용해 이미 존재하면 생략한다.
 *       재실행은 안전하다(결과 불변).</li>
 *   <li>append-only: DONE 정산·POSTED 원장을 수정하지 않는다. Payout 만 신규 생성.</li>
 *   <li>페이지 단위 커밋: 부분 실패 시 재실행으로 재개 가능하다.</li>
 * </ul>
 */
public interface BackfillMissingPayoutsUseCase {

    /**
     * 지정 기간의 미생성 Payout 을 페이지 단위로 백필한다.
     *
     * @param from          조회 시작일 (confirmed_at 기준 inclusive)
     * @param to            조회 종료일 (inclusive)
     * @param pageSizeOverride 페이지 크기 오버라이드 (null/비양수면 기본값)
     * @return 생성 건수·스킵 건수·잔여 건수·완료 여부
     */
    PayoutBackfillReport backfill(LocalDate from, LocalDate to, Integer pageSizeOverride);

    /**
     * 백필 실행 없이 미생성 Payout 잔여 건수만 조회한다.
     *
     * @return 실행 전후 상태 확인용 리포트
     */
    PayoutBackfillReport status(LocalDate from, LocalDate to);
}
