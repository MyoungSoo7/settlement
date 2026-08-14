package github.lms.lemuel.deposit.application.port.out;

import github.lms.lemuel.deposit.domain.DepositOffsetShortfall;
import github.lms.lemuel.deposit.domain.DepositShortfallStatus;

import java.util.List;
import java.util.Optional;

public interface LoadDepositOffsetShortfallPort {
    List<DepositOffsetShortfall> findByStatus(DepositShortfallStatus status);

    /** 단건 조회 — 운영자가 특정 부족분을 해소/상각할 때의 진입점. */
    Optional<DepositOffsetShortfall> findById(Long id);
}
