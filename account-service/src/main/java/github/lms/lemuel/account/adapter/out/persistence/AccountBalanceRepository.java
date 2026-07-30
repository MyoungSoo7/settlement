package github.lms.lemuel.account.adapter.out.persistence;

import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.account.domain.OwnerType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
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

    // ─── ADR 0030 Phase 3 — 대사(실체화 잔액 vs 원장 재합산) ─────────────────────
    // 재합산 CTE 는 Phase 1 백필과 동일한 식(전표 두 레그 UNION ALL, credit-positive)이다.
    // FULL OUTER JOIN 으로 어느 한쪽에만 있는 쌍(실체화 행 부재 / 고아 캐시 행)도 잡는다.
    //
    // 단일 문장인 이유(감사 MED-3): read_committed 는 문장마다 스냅샷을 갱신하므로 count·상세를
    // 쪼개면 자기모순 보고(driftCount=1, drifts=[])가 가능하다. 쌍 총수(checked_pairs)·드리프트 수
    // (drift_count, 윈도우 — LIMIT 이전에 계산)·상세를 한 문장에 담아 스냅샷을 하나로 만들고,
    // 원장 전량 스캔도 1회로 줄인다(CTE 는 2회 참조라 PG 가 materialize 한다).

    /**
     * 대사 스냅샷 — 드리프트 행 + 요약 카운트 2종을 한 문장으로 반환한다.
     * 드리프트 0 이면 빈 결과(요약은 어댑터가 {@link #countBalancePairs()} 로 보충).
     * 컬럼 순서(owner_type, owner_id, account, materialized, recomputed, drift_count, checked_pairs)는
     * 어댑터 매핑과 계약이다.
     */
    @Query(value = """
            WITH recomputed AS (
                SELECT owner_type, owner_id, account, SUM(delta) AS balance
                  FROM (
                        SELECT owner_type, owner_id, credit_account AS account,  amount AS delta
                          FROM opslab.account_entries
                        UNION ALL
                        SELECT owner_type, owner_id, debit_account  AS account, -amount AS delta
                          FROM opslab.account_entries
                       ) legs
                 GROUP BY owner_type, owner_id, account
            ),
            pairs AS (
                SELECT COALESCE(r.owner_type, m.owner_type) AS owner_type,
                       COALESCE(r.owner_id,  m.owner_id)  AS owner_id,
                       COALESCE(r.account,   m.account)   AS account,
                       COALESCE(m.balance, 0)             AS materialized,
                       COALESCE(r.balance, 0)             AS recomputed
                  FROM recomputed r
                  FULL OUTER JOIN opslab.account_balances m
                    ON m.owner_type = r.owner_type AND m.owner_id = r.owner_id AND m.account = r.account
            )
            SELECT owner_type, owner_id, account, materialized, recomputed,
                   COUNT(*) OVER ()                  AS drift_count,
                   (SELECT COUNT(*) FROM pairs)      AS checked_pairs
              FROM pairs
             WHERE materialized <> recomputed
             ORDER BY ABS(materialized - recomputed) DESC, owner_type, owner_id, account
             LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findBalanceReconRows(@Param("limit") int limit);

    /** 대조 대상 (owner, account) 쌍 총수 — 드리프트 0(빈 결과)일 때의 요약 보충용. */
    @Query(value = """
            SELECT COUNT(*) FROM (
                SELECT owner_type, owner_id, credit_account AS account FROM opslab.account_entries
                UNION
                SELECT owner_type, owner_id, debit_account  AS account FROM opslab.account_entries
                UNION
                SELECT owner_type, owner_id, account FROM opslab.account_balances
            ) pairs
            """, nativeQuery = true)
    long countBalancePairs();
}
