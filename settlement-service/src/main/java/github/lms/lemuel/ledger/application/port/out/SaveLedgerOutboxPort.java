package github.lms.lemuel.ledger.application.port.out;

import github.lms.lemuel.ledger.domain.LedgerOutboxTask;

import java.util.List;

/**
 * 원장 아웃박스 쓰기 아웃바운드 포트.
 */
public interface SaveLedgerOutboxPort {

    /** 신규 작업들을 PENDING 으로 적재 (호출자 트랜잭션에 참여). */
    void saveAll(List<LedgerOutboxTask> tasks);

    /** 처리 완료 마킹. */
    void markDone(Long taskId);

    /** 실패 마킹 — retry_count 증가, maxRetry 도달 시 FAILED. */
    void markFailed(Long taskId, String error, int maxRetry);

    /**
     * FAILED 작업을 id 오름차순 최대 limit 건 PENDING 으로 되돌린다(retry_count·last_error·processed_at 리셋).
     *
     * @return 실제 재큐된 건수
     */
    int requeueFailed(int limit);
}
