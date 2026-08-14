package github.lms.lemuel.account.banking.savings.application.service;

import github.lms.lemuel.account.banking.savings.application.port.in.QueryInstallmentSavingsUseCase;
import github.lms.lemuel.account.banking.savings.application.port.out.LoadInstallmentSavingsPort;
import github.lms.lemuel.account.banking.savings.domain.InstallmentSavings;
import github.lms.lemuel.account.banking.savings.domain.exception.SavingsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 적금 조회 — 단건은 소유권을 검사하고, 목록은 애초에 예금주로 조회해 남의 행이 섞일 여지를 없앤다.
 */
@Service
public class QueryInstallmentSavingsService implements QueryInstallmentSavingsUseCase {

    private final LoadInstallmentSavingsPort loadInstallmentSavingsPort;

    public QueryInstallmentSavingsService(LoadInstallmentSavingsPort loadInstallmentSavingsPort) {
        this.loadInstallmentSavingsPort = loadInstallmentSavingsPort;
    }

    @Override
    @Transactional(readOnly = true)
    public InstallmentSavings get(Long savingsId, String depositorId) {
        InstallmentSavings savings = loadInstallmentSavingsPort.findById(savingsId)
                .orElseThrow(() -> new SavingsNotFoundException(savingsId));
        savings.assertOwnedBy(depositorId);
        return savings;
    }

    @Override
    @Transactional(readOnly = true)
    public List<InstallmentSavings> listMine(String depositorId) {
        return loadInstallmentSavingsPort.findByDepositorId(depositorId);
    }
}
