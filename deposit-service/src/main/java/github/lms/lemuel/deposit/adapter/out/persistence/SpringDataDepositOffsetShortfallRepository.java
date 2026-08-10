package github.lms.lemuel.deposit.adapter.out.persistence;

import github.lms.lemuel.deposit.domain.DepositShortfallStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataDepositOffsetShortfallRepository
        extends JpaRepository<DepositOffsetShortfallJpaEntity, Long> {

    List<DepositOffsetShortfallJpaEntity> findByStatus(DepositShortfallStatus status);
}
