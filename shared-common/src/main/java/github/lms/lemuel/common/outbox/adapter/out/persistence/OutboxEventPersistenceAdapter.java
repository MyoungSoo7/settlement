package github.lms.lemuel.common.outbox.adapter.out.persistence;

import github.lms.lemuel.common.outbox.application.port.out.LoadOutboxEventPort;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.common.outbox.domain.OutboxEventStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class OutboxEventPersistenceAdapter implements SaveOutboxEventPort, LoadOutboxEventPort {

    private final SpringDataOutboxEventRepository repository;

    public OutboxEventPersistenceAdapter(SpringDataOutboxEventRepository repository) {
        this.repository = repository;
    }

    @Override
    public OutboxEvent save(OutboxEvent event) {
        OutboxEventJpaEntity entity = new OutboxEventJpaEntity(
                event.getId(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getEventType(),
                event.getEventId(),
                event.getPayload(),
                event.getStatus(),
                event.getRetryCount(),
                event.getLastError(),
                event.getCreatedAt(),
                event.getPublishedAt(),
                event.getTraceParent()
        );
        OutboxEventJpaEntity saved = repository.save(entity);
        return toDomain(saved);
    }

    @Override
    public List<OutboxEvent> findPending(int limit) {
        return repository
                .findByStatusOrderByCreatedAtAsc(OutboxEventStatus.PENDING, PageRequest.of(0, limit))
                .stream()
                .map(OutboxEventPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public long countPending() {
        return repository.countByStatus(OutboxEventStatus.PENDING);
    }

    @Override
    public List<OutboxEvent> findFailed(int offset, int limit) {
        int page = limit > 0 ? offset / limit : 0;
        return repository
                .findByStatusOrderByCreatedAtAsc(OutboxEventStatus.FAILED, PageRequest.of(page, limit))
                .stream()
                .map(OutboxEventPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public long countFailed() {
        return repository.countByStatus(OutboxEventStatus.FAILED);
    }

    @Override
    public Optional<OutboxEvent> findByEventId(UUID eventId) {
        return repository.findByEventId(eventId).map(OutboxEventPersistenceAdapter::toDomain);
    }

    private static OutboxEvent toDomain(OutboxEventJpaEntity e) {
        return OutboxEvent.rehydrate(
                e.getId(),
                e.getAggregateType(),
                e.getAggregateId(),
                e.getEventType(),
                e.getEventId(),
                e.getPayload(),
                e.getStatus(),
                e.getRetryCount(),
                e.getLastError(),
                e.getCreatedAt(),
                e.getPublishedAt(),
                e.getTraceParent()
        );
    }
}
