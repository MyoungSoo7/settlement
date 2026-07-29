package github.lms.lemuel.account.adapter.out.persistence;

import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.account.domain.OwnerType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/** 실체화 통제계정 잔액 리포지토리 (ADR 0030 Phase 1). 정본은 원장이고 이 테이블은 파생 캐시다. */
public interface AccountBalanceRepository extends JpaRepository<AccountBalanceJpaEntity, Long> {

    /** (owner, account) 유일키 조회 — 재합산 없이 O(1). */
    Optional<AccountBalanceJpaEntity> findByOwnerTypeAndOwnerIdAndAccount(OwnerType ownerType,
                                                                          String ownerId,
                                                                          GlAccount account);

    /**
     * 잔액에 델타를 <b>누적</b>한다(행이 없으면 델타를 초기값으로 생성).
     *
     * <p>read-then-write 가 아니라 DB 단일 문장으로 더하므로 동시 기표가 유실되지 않는다. 충돌 시
     * {@code DO UPDATE} 가 그 행에 행 잠금을 잡아 같은 (owner, account) 기표들이 자동 직렬화된다 —
     * Phase 2 에서 advisory 락을 걷어낼 수 있는 근거가 이것이다.
     *
     * <p>스키마 한정: 네이티브 쿼리는 커넥션 search_path 기준이라 {@code opslab} 을 명시한다
     * (엔티티의 무스키마 매핑은 {@code default_schema} 로 해석되지만 네이티브는 아니다).
     *
     * @param delta credit-positive 델타 — 대변 레그는 {@code +amount}, 차변 레그는 {@code -amount}
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO opslab.account_balances (owner_type, owner_id, account, balance, updated_at)
            VALUES (:ownerType, :ownerId, :account, :delta, NOW())
            ON CONFLICT (owner_type, owner_id, account)
            DO UPDATE SET balance    = opslab.account_balances.balance + EXCLUDED.balance,
                          updated_at = NOW()
            """, nativeQuery = true)
    int upsertDelta(@Param("ownerType") String ownerType,
                    @Param("ownerId") String ownerId,
                    @Param("account") String account,
                    @Param("delta") BigDecimal delta);
}
