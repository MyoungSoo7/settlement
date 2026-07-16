package github.lms.lemuel.ledger.adapter.out.persistence;

import github.lms.lemuel.ledger.application.port.out.LoadLedgerOutboxPort;
import github.lms.lemuel.ledger.application.port.out.SaveLedgerOutboxPort;
import github.lms.lemuel.ledger.domain.LedgerOutboxTask;
import github.lms.lemuel.ledger.domain.LedgerTaskType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
public class LedgerOutboxPersistenceAdapter implements SaveLedgerOutboxPort, LoadLedgerOutboxPort {

    private final SpringDataLedgerOutboxRepository repository;

    @Override
    public void saveAll(List<LedgerOutboxTask> tasks) {
        List<LedgerOutboxJpaEntity> entities = tasks.stream()
                .map(LedgerOutboxPersistenceAdapter::toEntity)
                .toList();
        repository.saveAll(entities);
    }

    @Override
    @Transactional
    public void markDone(Long taskId) {
        repository.markDone(taskId);
    }

    @Override
    @Transactional
    public void markFailed(Long taskId, String error, int maxRetry) {
        repository.markFailed(taskId, error, maxRetry);
    }

    @Override
    public List<LedgerOutboxTask> findPending(int limit) {
        return repository.findPending(PageRequest.of(0, limit)).stream()
                .map(LedgerOutboxPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public List<LedgerOutboxTask> findFailed(int limit) {
        return repository.findByStatusOrderByIdAsc("FAILED", PageRequest.of(0, limit)).stream()
                .map(LedgerOutboxPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public long countFailed() {
        return repository.countByStatus("FAILED");
    }

    @Override
    @Transactional
    public int requeueFailed(int limit) {
        List<Long> ids = repository.findFailedIds(PageRequest.of(0, limit));
        if (ids.isEmpty()) {
            return 0;
        }
        return repository.requeueByIds(ids);
    }

    private static LedgerOutboxJpaEntity toEntity(LedgerOutboxTask task) {
        LedgerOutboxJpaEntity e = new LedgerOutboxJpaEntity();
        e.setTaskType(task.type().name());
        e.setSettlementId(task.settlementId());
        e.setRefundId(task.refundId());
        e.setRefundAmount(task.refundAmount());
        e.setAdjustmentDate(task.adjustmentDate());
        return e;
    }

    private static LedgerOutboxTask toDomain(LedgerOutboxJpaEntity e) {
        return new LedgerOutboxTask(
                e.getId(),
                LedgerTaskType.valueOf(e.getTaskType()),
                e.getSettlementId(),
                e.getRefundId(),
                e.getRefundAmount(),
                e.getAdjustmentDate(),
                e.getRetryCount());
    }
}
