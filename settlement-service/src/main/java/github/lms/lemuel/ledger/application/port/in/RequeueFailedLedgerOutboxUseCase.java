package github.lms.lemuel.ledger.application.port.in;

import github.lms.lemuel.ledger.domain.LedgerOutboxTask;

import java.util.List;

/**
 * 원장 아웃박스 FAILED 항목 운영 인바운드 포트 — 운영자 콘솔이 구동한다.
 *
 * <p>재시도 한도를 넘겨 FAILED 로 고정된 원장 작업은 자동 복구되지 않는다(무한 재시도 폭주 방지).
 * 원인(예: 일시적 DB 이슈, 배포 중 스키마 불일치)이 해소된 뒤 운영자가 일괄 재큐해 폴러가 다시
 * 처리하게 한다. 상한(limit)으로 한 번에 되돌리는 양을 제한한다.
 */
public interface RequeueFailedLedgerOutboxUseCase {

    /** FAILED 작업 목록(id 오름차순, 최대 limit 건). */
    List<LedgerOutboxTask> listFailed(int limit);

    /** FAILED 행 수. */
    long countFailed();

    /**
     * FAILED 작업을 최대 limit 건 PENDING 으로 재큐(retry_count 리셋).
     *
     * @return 실제 재큐된 건수
     */
    int requeueFailed(int limit);
}
