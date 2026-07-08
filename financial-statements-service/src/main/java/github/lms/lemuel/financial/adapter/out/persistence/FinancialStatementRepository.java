package github.lms.lemuel.financial.adapter.out.persistence;

import github.lms.lemuel.financial.domain.FsDivision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FinancialStatementRepository extends JpaRepository<FinancialStatementJpaEntity, Long> {

    @Query("""
            SELECT f FROM FinancialStatementJpaEntity f
            WHERE f.stockCode = :stockCode
              AND (:fromYear IS NULL OR f.fiscalYear >= :fromYear)
              AND (:toYear IS NULL OR f.fiscalYear <= :toYear)
            ORDER BY f.fiscalYear DESC
            """)
    List<FinancialStatementJpaEntity> findByCompany(@Param("stockCode") String stockCode,
                                                    @Param("fromYear") Integer fromYear,
                                                    @Param("toYear") Integer toYear);

    Optional<FinancialStatementJpaEntity> findByStockCodeAndFiscalYearAndFsDiv(
            String stockCode, int fiscalYear, FsDivision fsDiv);
}
