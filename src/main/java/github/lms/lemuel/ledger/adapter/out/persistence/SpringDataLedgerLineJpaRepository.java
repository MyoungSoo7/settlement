package github.lms.lemuel.ledger.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface SpringDataLedgerLineJpaRepository extends JpaRepository<LedgerLineJpaEntity, Long> {

    @Query(nativeQuery = true, value = """
        SELECT COALESCE(SUM(CASE
            WHEN a.type IN ('ASSET','EXPENSE') AND ll.side = 'DEBIT' THEN ll.amount
            WHEN a.type IN ('ASSET','EXPENSE') AND ll.side = 'CREDIT' THEN -ll.amount
            WHEN a.type IN ('LIABILITY','REVENUE') AND ll.side = 'CREDIT' THEN ll.amount
            WHEN a.type IN ('LIABILITY','REVENUE') AND ll.side = 'DEBIT' THEN -ll.amount
            ELSE 0 END), 0)
        FROM ledger_lines ll
        JOIN accounts a ON a.id = ll.account_id
        WHERE ll.account_id = :accountId
        AND ll.created_at > :since
        """)
    BigDecimal calculateBalanceDeltaSince(@Param("accountId") Long accountId,
                                           @Param("since") LocalDateTime since);

    @Query(nativeQuery = true, value = """
        SELECT COALESCE(SUM(CASE
            WHEN a.type IN ('ASSET','EXPENSE') AND ll.side = 'DEBIT' THEN ll.amount
            WHEN a.type IN ('ASSET','EXPENSE') AND ll.side = 'CREDIT' THEN -ll.amount
            WHEN a.type IN ('LIABILITY','REVENUE') AND ll.side = 'CREDIT' THEN ll.amount
            WHEN a.type IN ('LIABILITY','REVENUE') AND ll.side = 'DEBIT' THEN -ll.amount
            ELSE 0 END), 0)
        FROM ledger_lines ll
        JOIN accounts a ON a.id = ll.account_id
        WHERE ll.account_id = :accountId
        """)
    BigDecimal calculateFullBalance(@Param("accountId") Long accountId);
}
