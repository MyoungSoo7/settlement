package github.lms.lemuel.ledger.application.port.in;

import github.lms.lemuel.ledger.domain.LedgerOutboxTask;

import java.util.List;

/**
 * 적재된 원장 아웃박스 작업을 처리하는 인바운드 포트 — 로컬 폴러가 구동한다.
 *
 * <p>처리(use case 호출)와 상태 마킹을 분리한 이유: 멱등 use case 는 자체 트랜잭션을 가지므로,
 * 마킹을 같은 트랜잭션에 묶으면 use case 예외가 트랜잭션을 rollback-only 로 만들어 FAILED 마킹까지
 * 못 남긴다. 따라서 폴러는 {@code execute} 성공 후 {@code markDone}, 실패 시 {@code markFailed}
 * 를 각각 별도 트랜잭션으로 호출한다.
 */
public interface ProcessLedgerOutboxPort {

    /** 미처리(PENDING) 작업을 id 오름차순으로 최대 limit 건 조회. */
    List<LedgerOutboxTask> fetchPending(int limit);

    /** 작업 1건을 대상 멱등 use case 로 실행. 실패 시 RuntimeException 전파. */
    void execute(LedgerOutboxTask task);

    /** 처리 완료 마킹 (별도 트랜잭션). */
    void markDone(Long taskId);

    /** 실패 마킹 — retry_count 증가, 한도 초과 시 FAILED (별도 트랜잭션). */
    void markFailed(Long taskId, String error);

    /**
     * 재시도 한도 — 이 횟수에 도달하면 markFailed 가 작업을 FAILED 로 고정한다.
     *
     * <p>폴러가 "이번 실패로 FAILED 로 전환됐는가"를 판정해 관제 신호를 쏘기 위해 노출한다
     * ({@code task.retryCount() + 1 >= maxRetry()} 이면 FAILED 전환).
     */
    int maxRetry();
}
