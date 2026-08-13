package github.lms.lemuel.deposit.adapter.out.persistence;

import github.lms.lemuel.deposit.application.port.out.LoadDepositOffsetShortfallPort;
import github.lms.lemuel.deposit.application.port.out.SaveDepositOffsetShortfallPort;
import github.lms.lemuel.deposit.domain.DepositOffsetShortfall;
import github.lms.lemuel.deposit.domain.DepositShortfallStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class DepositOffsetShortfallPersistenceAdapter
        implements SaveDepositOffsetShortfallPort, LoadDepositOffsetShortfallPort {

    private final SpringDataDepositOffsetShortfallRepository repo;

    public DepositOffsetShortfallPersistenceAdapter(SpringDataDepositOffsetShortfallRepository repo) {
        this.repo = repo;
    }

    @Override
    public DepositOffsetShortfall save(DepositOffsetShortfall shortfall) {
        DepositOffsetShortfallJpaEntity entity;
        if (shortfall.getId() == null) {
            entity = new DepositOffsetShortfallJpaEntity(
                    shortfall.getSellerId(), shortfall.getHolderType(), shortfall.getHolderReference(),
                    shortfall.getRequestedAmount(), shortfall.getAppliedAmount(),
                    shortfall.getShortfallAmount(), shortfall.getStatus(),
                    shortfall.getSourceHoldId(), shortfall.getOccurredAt());
        } else {
            entity = repo.findById(shortfall.getId())
                    .orElseThrow(() -> new IllegalStateException("Shortfall 을 찾을 수 없습니다: " + shortfall.getId()));
            entity.setAppliedAmount(shortfall.getAppliedAmount());
            entity.setShortfallAmount(shortfall.getShortfallAmount());
            entity.setStatus(shortfall.getStatus());
        }
        DepositOffsetShortfallJpaEntity saved = repo.save(entity);
        if (shortfall.getId() == null) {
            shortfall.assignId(saved.getId());
        }
        return shortfall;
    }

    @Override
    public List<DepositOffsetShortfall> findByStatus(DepositShortfallStatus status) {
        return repo.findByStatus(status).stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<DepositOffsetShortfall> findById(Long id) {
        return repo.findById(id).map(this::toDomain);
    }

    private DepositOffsetShortfall toDomain(DepositOffsetShortfallJpaEntity e) {
        return DepositOffsetShortfall.rehydrate(
                e.getId(), e.getSellerId(), e.getHolderType(), e.getHolderReference(),
                e.getRequestedAmount(), e.getAppliedAmount(), e.getShortfallAmount(),
                e.getStatus(), e.getSourceHoldId(), e.getOccurredAt());
    }
}
