package github.lms.lemuel.company.application.service;

import github.lms.lemuel.company.application.port.in.GetCompaniesUseCase;
import github.lms.lemuel.company.application.port.out.LoadCompanyPort;
import github.lms.lemuel.company.domain.Company;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class CompanyQueryService implements GetCompaniesUseCase {

    private static final int MAX_PAGE_SIZE = 100;

    private final LoadCompanyPort loadCompanyPort;

    public CompanyQueryService(LoadCompanyPort loadCompanyPort) {
        this.loadCompanyPort = loadCompanyPort;
    }

    @Override
    public CompanyPage search(String keyword, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        String normalized = keyword == null || keyword.isBlank() ? null : keyword.strip();
        LoadCompanyPort.SearchResult result = loadCompanyPort.search(normalized, safePage, safeSize);
        return new CompanyPage(result.content(), safePage, safeSize, result.totalElements());
    }

    @Override
    public Optional<Company> byStockCode(String stockCode) {
        return loadCompanyPort.findByStockCode(stockCode);
    }
}
