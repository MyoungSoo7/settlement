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
