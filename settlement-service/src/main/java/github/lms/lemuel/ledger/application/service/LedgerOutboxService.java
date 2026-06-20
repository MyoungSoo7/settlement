package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.port.in.EnqueueLedgerTaskPort;
import github.lms.lemuel.ledger.application.port.in.ProcessLedgerOutboxPort;
import github.lms.lemuel.ledger.application.port.in.CreateLedgerEntryUseCase;
import github.lms.lemuel.ledger.application.port.in.ReverseEntryUseCase;
import github.lms.lemuel.ledger.application.port.out.LoadLedgerOutboxPort;
import github.lms.lemuel.ledger.application.port.out.SaveLedgerOutboxPort;
import github.lms.lemuel.ledger.domain.LedgerOutboxTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 원장 트랜잭셔널 아웃박스 — 적재(enqueue)와 처리(process)를 담당.
 *
 * <p>적재: 정산/환불 서비스의 트랜잭션에 참여해 원장 작업을 같은 커밋으로 기록.
 * <p>처리: 로컬 폴러가 {@link ProcessLedgerOutboxPort} 로 구동. {@code execute} 는 이미 멱등인
 * {@link CreateLedgerEntryUseCase}/{@link ReverseEntryUseCase} 를 호출(자체 트랜잭션)하고,
 * 상태 마킹은 별도 트랜잭션으로 분리해 use case 예외가 마킹 트랜잭션을 오염시키지 않게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerOutboxService implements EnqueueLedgerTaskPort, ProcessLedgerOutboxPort {

    /** 재시도 한도 — 초과 시 FAILED 로 고정하고 운영자 개입 대상으로 남긴다. */
    private static final int MAX_RETRY = 10;
    /** last_error 컬럼 보호용 길이 제한. */
    private static final int MAX_ERROR_LEN = 1000;

    private final SaveLedgerOutboxPort saveLedgerOutboxPort;
    private final LoadLedgerOutboxPort loadLedgerOutboxPort;
    private final CreateLedgerEntryUseCase createLedgerEntryUseCase;
    private final ReverseEntryUseCase reverseEntryUseCase;

    // ── enqueue (호출자 트랜잭션에 참여 — 별도 @Transactional 없음) ──────────────

    @Override
    public void enqueueCreate(List<Long> settlementIds) {
        if (settlementIds == null || settlementIds.isEmpty()) return;
        List<LedgerOutboxTask> tasks = settlementIds.stream()
                .map(LedgerOutboxTask::create)
                .toList();
        saveLedgerOutboxPort.saveAll(tasks);
        log.debug("Ledger outbox enqueued: CREATE_ENTRY x{}", tasks.size());
    }

    @Override
    public void enqueueReverse(Long settlementId, Long refundId,
                               BigDecimal refundAmount, LocalDate adjustmentDate) {
        saveLedgerOutboxPort.saveAll(List.of(
                LedgerOutboxTask.reverse(settlementId, refundId, refundAmount, adjustmentDate)));
        log.debug("Ledger outbox enqueued: REVERSE_ENTRY settlementId={}, refundId={}",
                settlementId, refundId);
    }

    // ── process (폴러 구동) ────────────────────────────────────────────────────

    @Override
    public List<LedgerOutboxTask> fetchPending(int limit) {
        return loadLedgerOutboxPort.findPending(limit);
    }

    @Override
    public void execute(LedgerOutboxTask task) {
        switch (task.type()) {
            case CREATE_ENTRY -> createLedgerEntryUseCase.createFromSettlement(task.settlementId());
            case REVERSE_ENTRY -> reverseEntryUseCase.reverseForRefund(
                    task.settlementId(), task.refundId(), task.refundAmount(), task.adjustmentDate());
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDone(Long taskId) {
        saveLedgerOutboxPort.markDone(taskId);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long taskId, String error) {
        saveLedgerOutboxPort.markFailed(taskId, truncate(error), MAX_RETRY);
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= MAX_ERROR_LEN ? s : s.substring(0, MAX_ERROR_LEN);
    }
}
