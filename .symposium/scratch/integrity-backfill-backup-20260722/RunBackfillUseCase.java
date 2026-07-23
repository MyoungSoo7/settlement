package github.lms.lemuel.integrity.application.port.in;

import github.lms.lemuel.integrity.domain.BackfillReport;

import java.time.LocalDate;

/**
 * 과거 데이터 멱등 백필 유스케이스 — 탐지(무결성 스위트 재사용)와 정정(신규)을 분리한다.
 *
 * <p>append-only: DONE 정산·POSTED 원장을 수정하지 않고 신규 Payout·역분개 생성만 한다.
 * 반복 실행은 결과 불변(멱등) — (정산, 지급유형)·(reference, 계정쌍) UNIQUE 가 멱등 키다.
 */
public interface RunBackfillUseCase {

    /**
     * INV-6: 확정됐지만 Payout 이 없는 과거 정산에 즉시지급 Payout 을 생성한다.
     *
     * <p>탐지는 "비취소 Payout 이 하나도 없는 정산"(존재 기반)이라, 특정 유형만 누락된 정산
     * (예: HOLDBACK_RELEASE 만 있고 IMMEDIATE 없음)은 대상이 아니다 — 유형별 완전성은
     * 별도 탐지 확장의 몫이다(감사 INFO, 미지급 방향이라 이중 지급 위험 아님).
     */
    BackfillReport backfillPayouts(LocalDate from, LocalDate to, int pageSize, boolean dryRun);

    /** INV-5: 역분개 없는 조정(환불·차지백·PG대사)에 균형 역분개를 적재한다. */
    BackfillReport backfillAdjustmentReversals(LocalDate from, LocalDate to, int pageSize, boolean dryRun);
}
