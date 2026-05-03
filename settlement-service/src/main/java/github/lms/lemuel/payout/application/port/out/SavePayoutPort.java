package github.lms.lemuel.payout.application.port.out;

import github.lms.lemuel.payout.domain.Payout;

public interface SavePayoutPort {
    Payout save(Payout payout);
}
