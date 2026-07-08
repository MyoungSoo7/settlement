package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.GetCompanyReputationUseCase;
import github.lms.lemuel.loan.application.port.in.IngestCompanyReputationUseCase;
import github.lms.lemuel.loan.application.port.out.LoadCompanyReputationPort;
import github.lms.lemuel.loan.application.port.out.SaveCompanyReputationPort;
import github.lms.lemuel.loan.application.port.out.SaveSellerReputationPort;
import github.lms.lemuel.loan.domain.CompanyReputation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * CompanyReputationChanged 이벤트를 로컬 평판 프로젝션으로 적재하고 조회를 제공한다.
 * 종목코드가 식별자이므로 재수신 시 멱등 UPSERT (컨슈머 측 processed_events 와 이중 방어).
 *
 * <p>기업 단위 프로젝션(company_reputation)과 함께, 동봉된 링크 셀러별 프로젝션(seller_reputation)을
 * 적재해 CreditPolicy 가 셀러 신용 한도에 평판을 반영할 수 있게 한다.
 */
@Service
public class CompanyReputationService implements IngestCompanyReputationUseCase, GetCompanyReputationUseCase {

    private final SaveCompanyReputationPort saveCompanyReputationPort;
    private final LoadCompanyReputationPort loadCompanyReputationPort;
    private final SaveSellerReputationPort saveSellerReputationPort;

    public CompanyReputationService(SaveCompanyReputationPort saveCompanyReputationPort,
                                    LoadCompanyReputationPort loadCompanyReputationPort,
                                    SaveSellerReputationPort saveSellerReputationPort) {
        this.saveCompanyReputationPort = saveCompanyReputationPort;
        this.loadCompanyReputationPort = loadCompanyReputationPort;
        this.saveSellerReputationPort = saveSellerReputationPort;
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
        List<Long> sellerIds = command.sellerIds() == null ? List.of() : command.sellerIds();
        for (Long sellerId : sellerIds) {
            saveSellerReputationPort.upsert(sellerId, command.stockCode(), command.score(), command.grade());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CompanyReputation> byStockCode(String stockCode) {
        return loadCompanyReputationPort.findByStockCode(stockCode);
    }
}
