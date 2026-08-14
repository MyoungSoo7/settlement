package github.lms.lemuel.account.banking.pension.application.port.out;

import github.lms.lemuel.account.banking.pension.domain.RetirementPension;

import java.util.List;
import java.util.Optional;

/**
 * 퇴직연금 계약 조회 아웃바운드 포트 (거래 이력 포함 복원).
 */
public interface LoadRetirementPensionPort {

    Optional<RetirementPension> findById(Long pensionId);

    List<RetirementPension> findBySubscriberId(String subscriberId);
}
