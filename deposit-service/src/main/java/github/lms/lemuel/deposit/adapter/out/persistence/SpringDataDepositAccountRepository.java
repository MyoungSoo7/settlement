package github.lms.lemuel.deposit.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface SpringDataDepositAccountRepository extends JpaRepository<DepositAccountJpaEntity, Long> {

    Optional<DepositAccountJpaEntity> findBySellerId(Long sellerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM DepositAccountJpaEntity a WHERE a.sellerId = :sellerId")
    Optional<DepositAccountJpaEntity> findBySellerIdForUpdate(Long sellerId);
}
