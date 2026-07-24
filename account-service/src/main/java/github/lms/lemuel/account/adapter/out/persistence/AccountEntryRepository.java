package github.lms.lemuel.account.adapter.out.persistence;

import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.account.domain.OwnerType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface AccountEntryRepository extends JpaRepository<AccountEntryJpaEntity, Long> {

    /**
     * 레이스-세이프 멱등 삽입 (LOW-1) — 자연키 {@code (source_topic, ref_type, ref_id)} 충돌 시
     * {@code ON CONFLICT DO NOTHING} 으로 원자적 no-op. check-then-save 의 TOCTOU(동시 중복 수신 시
     * 둘째가 UNIQUE 위반 예외로 tx 를 rollback-only 오염)를 제거한다 — 위반을 catch 하지 않으므로 tx 가
     * 깨끗하게 유지된다. enum 컬럼은 {@code @Enumerated(STRING)} 표현과 맞춰 {@code .name()} 문자열로 바인딩한다.
     *
     * @return 삽입된 행 수(신규=1, 중복이면 0)
     */
    // 스키마 한정 필수: account 물리 DB(lemuel_account)의 논리 스키마는 opslab 이다(application.yml
    // hibernate.default_schema=opslab · flyway.schemas=opslab). Hibernate 매핑 엔티티는 default_schema 로
    // 자동 한정되지만 네이티브 쿼리는 커넥션 search_path 기준이라 opslab 을 명시해야 account_entries 가 해석된다.
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO opslab.account_entries
                (owner_type, owner_id, debit_account, credit_account, amount,
                 ref_type, ref_id, source_topic, occurred_at)
            VALUES
                (:ownerType, :ownerId, :debitAccount, :creditAccount, :amount,
                 :refType, :refId, :sourceTopic, :occurredAt)
            ON CONFLICT (source_topic, ref_type, ref_id) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoreConflict(@Param("ownerType") String ownerType,
                             @Param("ownerId") String ownerId,
                             @Param("debitAccount") String debitAccount,
                             @Param("creditAccount") String creditAccount,
                             @Param("amount") BigDecimal amount,
                             @Param("refType") String refType,
                             @Param("refId") String refId,
                             @Param("sourceTopic") String sourceTopic,
                             @Param("occurredAt") LocalDateTime occurredAt);

    List<AccountEntryJpaEntity> findByOwnerTypeAndOwnerIdOrderByIdDesc(OwnerType ownerType, String ownerId);

    List<AccountEntryJpaEntity> findByOwnerTypeAndOwnerId(OwnerType ownerType, String ownerId, Pageable pageable);

    long countByOwnerTypeAndOwnerId(OwnerType ownerType, String ownerId);

    long countByRefType(String refType);

    /** occurred_at 반개구간 [from, to) — 기간 확정 시산표 계산용. */
    List<AccountEntryJpaEntity> findByOccurredAtGreaterThanEqualAndOccurredAtLessThan(
            LocalDateTime fromInclusive, LocalDateTime toExclusive);

    /** refType 별 금액 합계. 매칭 행이 없으면 COALESCE 로 0 반환(null 미노출). */
    @Query("""
            select coalesce(sum(e.amount), 0)
            from AccountEntryJpaEntity e
            where e.refType = :refType
            """)
    BigDecimal sumAmountByRefType(@Param("refType") String refType);

    /**
     * owner 의 특정 계정 순잔액(credit합 − debit합). 한 전표 안에서 계정이 차변/대변 어느 쪽에 나와도
     * CASE 로 부호를 잡아 집계한다. 매칭 행이 없어도 COALESCE 로 0 반환(null 미노출).
     */
    @Query("""
            select coalesce(sum(case when e.creditAccount = :account then e.amount else 0 end), 0)
                 - coalesce(sum(case when e.debitAccount = :account then e.amount else 0 end), 0)
            from AccountEntryJpaEntity e
            where e.ownerType = :ownerType and e.ownerId = :ownerId
            """)
    BigDecimal netBalanceByOwnerAndAccount(@Param("ownerType") OwnerType ownerType,
                                           @Param("ownerId") String ownerId,
                                           @Param("account") GlAccount account);
}
