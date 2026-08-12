package github.lms.lemuel.loan.application.port.out;

import github.lms.lemuel.loan.domain.LeaseContract;

import java.util.List;
import java.util.Optional;

/**
 * 리스·할부 계약 조회 아웃바운드 포트.
 */
public interface LoadLeaseContractPort {

    Optional<LeaseContract> findById(Long contractId);

    /** 상태 전이 전용 — 비관적 락으로 조회해 동시 수납·해지의 경합을 차단한다. */
    Optional<LeaseContract> findByIdForUpdate(Long contractId);

    /** 차주 본인 계약 최신순 상위 {@code limit} 건 — 소유권 스코핑용. */
    List<LeaseContract> findByBorrower(Long borrowerUserId, int limit);
}
