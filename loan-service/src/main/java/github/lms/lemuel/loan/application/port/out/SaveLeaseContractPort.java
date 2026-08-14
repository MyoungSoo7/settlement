package github.lms.lemuel.loan.application.port.out;

import github.lms.lemuel.loan.domain.LeaseContract;

/**
 * 리스·할부 계약 저장 아웃바운드 포트.
 */
public interface SaveLeaseContractPort {

    LeaseContract save(LeaseContract contract);
}
