package github.lms.lemuel.ledger.application.port.in;

import github.lms.lemuel.ledger.domain.LedgerReverseBackfillReport;

/**
 * 원장 역분개 누락 백필 유스케이스.
 *
 * <p>INV-5 의 {@code missingReverseAdjustmentIds} 에서 탐지된 차지백·PG 대사 조정 중
 * 역분개가 없는 건을 멱등·append-only 로 보정한다. 새 탐지 로직을 중복 작성하지 않고
 * 기존 무결성 스위트의 쿼리 기준을 그대로 재사용한다.
 *
 * <p>백필은 {@code ledger_outbox} 에 작업을 적재하는 것까지만 담당하고,
 * 실제 역분개 분개 생성은 기존 {@code LedgerOutboxPoller} 가 비동기로 처리한다.
 * 멱등 보장: {@code ReverseEntryService.existsByReference} (앱 레벨) +
 * {@code uq_ledger_reference_accounts} (DB 레벨).
 */
public interface BackfillMissingReverseUseCase {

    /**
     * 역분개 없는 차지백·PG 대사 조정에 대해 {@code ledger_outbox} 작업을 페이지 단위로 적재한다.
     * 부분 실패 후 재실행이 안전하다(멱등).
     *
     * @param pageSizeOverride 페이지 크기 오버라이드 (null/비양수 시 기본값, 상한 초과 시 클램프)
     * @return 적재 건수·잔여 누락 건수·완료 여부를 담은 리포트
     */
    LedgerReverseBackfillReport backfillMissingReverse(Integer pageSizeOverride);

    /** 실행 없이 현재 역분개 누락 건 수만 조회한다 (사전/사후 검증용). */
    LedgerReverseBackfillReport statusMissingReverse();
}
