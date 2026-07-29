package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.domain.SecuredLoanStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SecuredLoanRepository extends JpaRepository<SecuredLoanJpaEntity, Long> {

    /** 실행·상환 전용 — 비관적 락으로 조회해 동시 이중지급/이중차감을 차단한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from SecuredLoanJpaEntity l where l.id = :id")
    Optional<SecuredLoanJpaEntity> findByIdForUpdate(@Param("id") Long id);

    /** 차주 본인 대출 목록(최신순) — 소유권 스코핑된 조회. */
    List<SecuredLoanJpaEntity> findByBorrowerUserIdOrderByIdDesc(Long borrowerUserId, Pageable pageable);

    /** 연체 판정 배치 대상 — 실행 중/연체 중 대출만. */
    List<SecuredLoanJpaEntity> findByStatusInOrderByIdAsc(List<SecuredLoanStatus> statuses);
}
