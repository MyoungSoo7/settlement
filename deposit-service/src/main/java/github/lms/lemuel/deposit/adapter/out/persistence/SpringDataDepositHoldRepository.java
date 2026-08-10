package github.lms.lemuel.deposit.adapter.out.persistence;

import github.lms.lemuel.deposit.domain.DepositHoldStatus;
import github.lms.lemuel.deposit.domain.DepositHolderType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SpringDataDepositHoldRepository extends JpaRepository<DepositHoldJpaEntity, Long> {

    Optional<DepositHoldJpaEntity> findByHolderTypeAndHolderReference(
            DepositHolderType holderType, String holderReference);

    @Query("SELECT h FROM DepositHoldJpaEntity h WHERE h.status = :status AND h.expiresAt < :cutoff")
    List<DepositHoldJpaEntity> findByStatusAndExpiresAtBefore(DepositHoldStatus status, LocalDateTime cutoff);
}
