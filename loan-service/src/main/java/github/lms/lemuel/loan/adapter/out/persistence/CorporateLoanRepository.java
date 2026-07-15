package github.lms.lemuel.loan.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CorporateLoanRepository extends JpaRepository<CorporateLoanJpaEntity, Long> {

    List<CorporateLoanJpaEntity> findByStockCodeOrderByIdDesc(String stockCode);

    List<CorporateLoanJpaEntity> findAllByOrderByIdDesc(Pageable pageable);

    /**
     * 실행(disburse) 전용 — 행 비관적 락(SELECT ... FOR UPDATE). 동시 disburse 요청 시
     * 두 번째 트랜잭션을 첫 커밋까지 블로킹해 이중지급(전표·이벤트 중복)을 차단한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CorporateLoanJpaEntity c WHERE c.id = :id")
    Optional<CorporateLoanJpaEntity> findByIdForUpdate(@Param("id") Long id);
}
