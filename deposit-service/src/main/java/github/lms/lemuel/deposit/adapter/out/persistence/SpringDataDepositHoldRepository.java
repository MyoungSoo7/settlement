package github.lms.lemuel.deposit.adapter.out.persistence;

import github.lms.lemuel.deposit.domain.DepositHoldStatus;
import github.lms.lemuel.deposit.domain.DepositHolderType;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SpringDataDepositHoldRepository extends JpaRepository<DepositHoldJpaEntity, Long> {

    Optional<DepositHoldJpaEntity> findByHolderTypeAndHolderReference(
            DepositHolderType holderType, String holderReference);

    /**
     * 만료 회수 스캔 — 재원을 아직 잡고 있는 상태만 본다.
     *
     * <p>status 를 단수로 받던 이전 형태는 ACTIVE 만 조회해 PARTIALLY_CAPTURED 의 잔여를 놓쳤다.
     * 부분 인덱스 {@code idx_deposit_holds_unsettled_expiring} 가 같은 두 상태를 덮는다 —
     * 여기 목록을 바꾸면 인덱스 술어도 함께 바꿔야 한다(V20260813120000).
     */
    @Query("SELECT h FROM DepositHoldJpaEntity h WHERE h.status IN :statuses AND h.expiresAt < :cutoff")
    List<DepositHoldJpaEntity> findByStatusInAndExpiresAtBefore(Collection<DepositHoldStatus> statuses,
                                                                 LocalDateTime cutoff);
}
