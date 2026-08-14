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

    /** PK 진입점 — hold 는 sellerId 가 아니라 accountId 를 들고 있어 만료 회수 경로에 필요하다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM DepositAccountJpaEntity a WHERE a.id = :accountId")
    Optional<DepositAccountJpaEntity> findByIdForUpdate(Long accountId);
}
