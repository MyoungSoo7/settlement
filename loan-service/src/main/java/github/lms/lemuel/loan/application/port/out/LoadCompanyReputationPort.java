package github.lms.lemuel.loan.application.port.out;

import github.lms.lemuel.loan.domain.CompanyReputation;

import java.util.Optional;

public interface LoadCompanyReputationPort {

    Optional<CompanyReputation> findByStockCode(String stockCode);
}
