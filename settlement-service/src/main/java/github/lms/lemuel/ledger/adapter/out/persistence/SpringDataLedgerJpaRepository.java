package github.lms.lemuel.ledger.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface SpringDataLedgerJpaRepository extends JpaRepository<LedgerEntryJpaEntity, Long> {

    boolean existsByReferenceIdAndReferenceType(Long referenceId, String referenceType);

    List<LedgerEntryJpaEntity> findByReferenceIdAndReferenceType(Long referenceId, String referenceType);

    List<LedgerEntryJpaEntity> findBySettlementDateBetween(LocalDate from, LocalDate to);

    /**
     * 기간 확정 시산표 — POSTED 분개의 차변계정별 amount 합계.
     * 반환: {@code [debitAccount(String), sum(BigDecimal)]} 행 목록.
     *
     * <p>차변/대변 2쿼리는 의도적 유지 — 각각 커버링 인덱스
     * {@code idx_ledger_date_debit/credit (settlement_date, 계정) INCLUDE (amount, status)}
     * (V20260807120000)의 Index-Only Scan 을 탄다. LATERAL 언피벗 단일 스캔 병합은 커버링을
     * 포기하고 힙을 읽어 2M 행 실측에서 오히려 느렸다(2스캔 합 ~50ms vs 병합 88ms —
     * docs/inflearn/db-perf.md 실측).
     */
    @Query("""
            select e.debitAccount, sum(e.amount)
            from LedgerEntryJpaEntity e
            where e.status = 'POSTED' and e.settlementDate between :from and :to
            group by e.debitAccount
            """)
    List<Object[]> sumPostedDebitByAccount(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * 기간 확정 시산표 — POSTED 분개의 대변계정별 amount 합계.
     * 반환: {@code [creditAccount(String), sum(BigDecimal)]} 행 목록.
     */
    @Query("""
            select e.creditAccount, sum(e.amount)
            from LedgerEntryJpaEntity e
            where e.status = 'POSTED' and e.settlementDate between :from and :to
            group by e.creditAccount
            """)
    List<Object[]> sumPostedCreditByAccount(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
