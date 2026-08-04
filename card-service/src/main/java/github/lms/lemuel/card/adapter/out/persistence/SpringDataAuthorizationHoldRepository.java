package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.domain.HoldStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SpringDataAuthorizationHoldRepository
        extends JpaRepository<AuthorizationHoldJpaEntity, Long> {

    /** 자연키(authorization_id)로 조회 — 멱등 체크 전용. */
    Optional<AuthorizationHoldJpaEntity> findByAuthorizationId(String authorizationId);

    /** 자연키(authorization_id)로 비관적 락 조회 — 매입·취소·환불 시 동시 상태 변경 방어. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from AuthorizationHoldJpaEntity h where h.authorizationId = :authorizationId")
    Optional<AuthorizationHoldJpaEntity> findByAuthorizationIdForUpdate(
            @Param("authorizationId") String authorizationId);

    /**
     * 카드계정의 ACTIVE 홀드 합계 — master 가용한도 계산.
     * 비관적 락(findByIdForUpdate) 획득 후 호출해야 정합성이 보장된다.
     */
    @Query("""
            select coalesce(sum(h.amount), 0)
              from AuthorizationHoldJpaEntity h
             where h.cardAccountId = :cardAccountId
               and h.status = 'ACTIVE'
            """)
    BigDecimal sumActiveHoldsByAccount(@Param("cardAccountId") Long cardAccountId);

    /** 카드의 ACTIVE 홀드 합계 — 서브한도 가용한도 계산. */
    @Query("""
            select coalesce(sum(h.amount), 0)
              from AuthorizationHoldJpaEntity h
             where h.cardId = :cardId
               and h.status = 'ACTIVE'
            """)
    BigDecimal sumActiveHoldsByCard(@Param("cardId") Long cardId);

    /**
     * 카드의 특정 구간 지출 합계(ACTIVE + CAPTURED) — 일·월 한도 평가용.
     * authorized_at 기준: 승인 시각으로 귀속일을 판단한다.
     */
    @Query("""
            select coalesce(sum(h.amount), 0)
              from AuthorizationHoldJpaEntity h
             where h.cardId = :cardId
               and h.status in ('ACTIVE', 'CAPTURED', 'PARTIALLY_CAPTURED')
               and h.authorizedAt >= :from
               and h.authorizedAt < :to
            """)
    BigDecimal sumHoldsByCardAndPeriod(@Param("cardId") Long cardId,
                                       @Param("from") Instant from,
                                       @Param("to") Instant to);

    /**
     * 지정 시각 이전에 승인되고 아직 ACTIVE 상태인 홀드 목록 — 만료 배치 전용.
     */
    @Query("""
            select h from AuthorizationHoldJpaEntity h
             where h.status = 'ACTIVE'
               and h.authorizedAt < :before
            """)
    List<AuthorizationHoldJpaEntity> findAllActiveAuthorizedBefore(@Param("before") Instant before);
}
