package github.lms.lemuel.company.application.service;

import github.lms.lemuel.company.application.port.in.GetCompanyWorkforceUseCase;
import github.lms.lemuel.company.application.port.out.LoadCompanyWorkforcePort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CompanyWorkforceQueryService implements GetCompanyWorkforceUseCase {

    private static final int MAX_PAGE_SIZE = 100;

    private final LoadCompanyWorkforcePort loadCompanyWorkforcePort;

    public CompanyWorkforceQueryService(LoadCompanyWorkforcePort loadCompanyWorkforcePort) {
        this.loadCompanyWorkforcePort = loadCompanyWorkforcePort;
    }

    @Override
    public WorkforcePage search(String workplaceName, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        String normalized = workplaceName == null || workplaceName.isBlank() ? null : workplaceName.strip();
        LoadCompanyWorkforcePort.SearchResult result = loadCompanyWorkforcePort.search(normalized, safePage, safeSize);
        return new WorkforcePage(result.content(), safePage, safeSize, result.totalElements());
    }
}
