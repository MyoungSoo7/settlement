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
}
