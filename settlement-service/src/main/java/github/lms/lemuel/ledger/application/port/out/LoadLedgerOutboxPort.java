package github.lms.lemuel.ledger.application.port.out;

import github.lms.lemuel.ledger.domain.LedgerOutboxTask;

import java.util.List;

/**
 * 원장 아웃박스 읽기 아웃바운드 포트.
 */
public interface LoadLedgerOutboxPort {

    /** PENDING 작업을 id 오름차순으로 최대 limit 건. */
    List<LedgerOutboxTask> findPending(int limit);

    /** FAILED 작업을 id 오름차순으로 최대 limit 건 (운영자 조회용). */
    List<LedgerOutboxTask> findFailed(int limit);

    /** FAILED 행 수. */
    long countFailed();
}
