package github.lms.lemuel.financial.application.service;

import github.lms.lemuel.financial.application.port.in.GetFinancialStatementsUseCase;
import github.lms.lemuel.financial.application.port.out.LoadCompanyPort;
import github.lms.lemuel.financial.application.port.out.LoadFinancialStatementPort;
import github.lms.lemuel.financial.domain.FinancialStatement;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

@Service
@Transactional(readOnly = true)
public class FinancialStatementQueryService implements GetFinancialStatementsUseCase {

    private final LoadCompanyPort loadCompanyPort;
    private final LoadFinancialStatementPort loadFinancialStatementPort;

    public FinancialStatementQueryService(LoadCompanyPort loadCompanyPort,
                                          LoadFinancialStatementPort loadFinancialStatementPort) {
        this.loadCompanyPort = loadCompanyPort;
        this.loadFinancialStatementPort = loadFinancialStatementPort;
    }

    @Override
    public List<FinancialStatement> byCompany(String stockCode, Integer fromYear, Integer toYear) {
        loadCompanyPort.findByStockCode(stockCode)
                .orElseThrow(() -> new NoSuchElementException("기업을 찾을 수 없습니다: " + stockCode));
        if (fromYear != null && toYear != null && fromYear > toYear) {
            throw new IllegalArgumentException("fromYear 가 toYear 보다 큽니다: %d > %d".formatted(fromYear, toYear));
        }
        return loadFinancialStatementPort.findByCompany(stockCode, fromYear, toYear);
    }
}
