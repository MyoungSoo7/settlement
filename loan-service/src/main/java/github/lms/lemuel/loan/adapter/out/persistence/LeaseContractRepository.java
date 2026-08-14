package github.lms.lemuel.loan.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

/**
 * 리스·할부 계약 저장소.
 */
public interface LeaseContractRepository extends JpaRepository<LeaseContractJpaEntity, Long> {

    /**
     * 상태 전이 전용 비관적 락 조회 — 회차 수납·해지가 동시에 들어오면 납입 회차가 덮어써지거나
     * 종료된 계약에 다시 손해금이 매겨질 수 있다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<LeaseContractJpaEntity> findWithLockById(Long id);

    /** 차주 본인 계약 최신순 — 소유권 스코핑 조회. */
    List<LeaseContractJpaEntity> findByBorrowerUserIdOrderByIdDesc(Long borrowerUserId, Limit limit);
}
