package github.lms.lemuel.deposit.adapter.out.persistence;

import github.lms.lemuel.deposit.application.port.out.LoadDepositHoldPort;
import github.lms.lemuel.deposit.application.port.out.SaveDepositHoldPort;
import github.lms.lemuel.deposit.domain.DepositHold;
import github.lms.lemuel.deposit.domain.DepositHoldStatus;
import github.lms.lemuel.deposit.domain.DepositHolderType;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Component
public class DepositHoldPersistenceAdapter implements LoadDepositHoldPort, SaveDepositHoldPort {

    private final SpringDataDepositHoldRepository repo;

    public DepositHoldPersistenceAdapter(SpringDataDepositHoldRepository repo) {
        this.repo = repo;
    }

    @Override
    public Optional<DepositHold> findByHolderTypeAndReference(DepositHolderType holderType, String holderReference) {
        return repo.findByHolderTypeAndHolderReference(holderType, holderReference).map(this::toDomain);
    }

    @Override
    public List<DepositHold> findExpiredStillHolding(LocalDateTime cutoff) {
        return repo.findByStatusInAndExpiresAtBefore(
                        List.of(DepositHoldStatus.ACTIVE, DepositHoldStatus.PARTIALLY_CAPTURED), cutoff)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public DepositHold save(DepositHold hold) {
        DepositHoldJpaEntity entity;
        if (hold.getId() == null) {
            entity = new DepositHoldJpaEntity(
                    hold.getAccountId(), hold.getHolderType(), hold.getHolderReference(),
                    hold.getOriginalAmount(), hold.getRemainingAmount(),
                    hold.getStatus(), hold.getExpiresAt());
        } else {
            entity = repo.findById(hold.getId())
                    .orElseThrow(() -> new IllegalStateException("Hold 를 찾을 수 없습니다: " + hold.getId()));
            entity.setRemainingAmount(hold.getRemainingAmount());
            entity.setStatus(hold.getStatus());
        }
        DepositHoldJpaEntity saved = repo.save(entity);
        if (hold.getId() == null) {
            hold.assignId(saved.getId());
        }
        return hold;
    }

    private DepositHold toDomain(DepositHoldJpaEntity e) {
        return DepositHold.rehydrate(
                e.getId(), e.getAccountId(),
                e.getHolderType(), e.getHolderReference(),
                e.getOriginalAmount(), e.getRemainingAmount(),
                e.getStatus(), e.getExpiresAt(),
                e.getCreatedAt(), e.getUpdatedAt(), e.getVersion());
    }
}
