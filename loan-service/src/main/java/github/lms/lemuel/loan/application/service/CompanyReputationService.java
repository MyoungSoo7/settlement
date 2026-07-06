package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.GetCompanyReputationUseCase;
import github.lms.lemuel.loan.application.port.in.IngestCompanyReputationUseCase;
import github.lms.lemuel.loan.application.port.out.LoadCompanyReputationPort;
import github.lms.lemuel.loan.application.port.out.SaveCompanyReputationPort;
import github.lms.lemuel.loan.domain.CompanyReputation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * CompanyReputationChanged 이벤트를 로컬 평판 프로젝션으로 적재하고 조회를 제공한다.
 * 종목코드가 식별자이므로 재수신 시 멱등 UPSERT (컨슈머 측 processed_events 와 이중 방어).
 */
@Service
public class CompanyReputationService implements IngestCompanyReputationUseCase, GetCompanyReputationUseCase {

    private final SaveCompanyReputationPort saveCompanyReputationPort;
    private final LoadCompanyReputationPort loadCompanyReputationPort;

    public CompanyReputationService(SaveCompanyReputationPort saveCompanyReputationPort,
                                    LoadCompanyReputationPort loadCompanyReputationPort) {
        this.saveCompanyReputationPort = saveCompanyReputationPort;
        this.loadCompanyReputationPort = loadCompanyReputationPort;
    }

    @Override
    @Transactional
    public void ingest(IngestCompanyReputationCommand command) {
        saveCompanyReputationPort.upsert(new CompanyReputation(
                command.stockCode(),
                command.score(),
                command.grade(),
                command.previousGrade(),
                command.snapshotDate()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CompanyReputation> byStockCode(String stockCode) {
        return loadCompanyReputationPort.findByStockCode(stockCode);
    }
}
