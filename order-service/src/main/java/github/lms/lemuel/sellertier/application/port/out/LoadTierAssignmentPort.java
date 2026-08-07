package github.lms.lemuel.sellertier.application.port.out;

import github.lms.lemuel.sellertier.domain.TierAssignment;

import java.util.Optional;

public interface LoadTierAssignmentPort {

    /** 없으면 empty — 처음 평가되는 셀러는 NORMAL 에서 시작한다. */
    Optional<TierAssignment> findBySellerId(Long sellerId);
}
