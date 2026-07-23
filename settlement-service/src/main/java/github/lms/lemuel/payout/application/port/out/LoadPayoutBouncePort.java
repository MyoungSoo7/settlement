package github.lms.lemuel.payout.application.port.out;

import github.lms.lemuel.payout.domain.PayoutBounce;

import java.util.Optional;

public interface LoadPayoutBouncePort {

    /** 반송 멱등 조회 — payout 당 반송은 최대 1건. */
    Optional<PayoutBounce> findByPayoutId(Long payoutId);
}
