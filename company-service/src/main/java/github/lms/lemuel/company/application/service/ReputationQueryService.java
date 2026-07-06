package github.lms.lemuel.company.application.service;

import github.lms.lemuel.company.application.port.in.GetReputationUseCase;
import github.lms.lemuel.company.application.port.out.LoadCompanyPort;
import github.lms.lemuel.company.application.port.out.LoadReputationPort;
import github.lms.lemuel.company.domain.ReputationScore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class ReputationQueryService implements GetReputationUseCase {

    private static final int MAX_HISTORY = 365;

    private final LoadCompanyPort loadCompanyPort;
    private final LoadReputationPort loadReputationPort;

    public ReputationQueryService(LoadCompanyPort loadCompanyPort, LoadReputationPort loadReputationPort) {
        this.loadCompanyPort = loadCompanyPort;
        this.loadReputationPort = loadReputationPort;
    }

    @Override
    public Optional<ReputationScore> current(String stockCode) {
        requireCompany(stockCode);
        return loadReputationPort.findLatest(stockCode);
    }

    @Override
    public List<ReputationScore> history(String stockCode, int limit) {
        requireCompany(stockCode);
        int safeLimit = Math.min(Math.max(limit, 1), MAX_HISTORY);
        return loadReputationPort.findHistory(stockCode, safeLimit);
    }

    private void requireCompany(String stockCode) {
        loadCompanyPort.findByStockCode(stockCode)
                .orElseThrow(() -> new NoSuchElementException("기업을 찾을 수 없습니다: " + stockCode));
    }
}
