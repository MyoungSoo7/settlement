package github.lms.lemuel.financial.adapter.out.persistence;

import github.lms.lemuel.financial.application.port.out.LoadFinancialStatementPort;
import github.lms.lemuel.financial.application.port.out.SaveFinancialStatementPort;
import github.lms.lemuel.financial.domain.FinancialStatement;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class FinancialStatementPersistenceAdapter
        implements LoadFinancialStatementPort, SaveFinancialStatementPort {

    private final FinancialStatementRepository repository;

    public FinancialStatementPersistenceAdapter(FinancialStatementRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<FinancialStatement> findByCompany(String stockCode, Integer fromYear, Integer toYear) {
        return repository.findByCompany(stockCode, fromYear, toYear).stream()
                .map(FinancialStatementJpaEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public void upsert(FinancialStatement statement) {
        FinancialStatementJpaEntity entity = repository
                .findByStockCodeAndFiscalYearAndFsDiv(
                        statement.stockCode(), statement.fiscalYear(), statement.fsDivision())
                .map(existing -> {
                    existing.applyDomain(statement);
                    return existing;
                })
                .orElseGet(() -> FinancialStatementJpaEntity.fromDomain(statement));
        repository.save(entity);
    }
}
