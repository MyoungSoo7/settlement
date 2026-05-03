package github.lms.lemuel.payout.application.port.in;

import github.lms.lemuel.payout.domain.Payout;

public interface RetryFailedPayoutUseCase {

    Payout retry(Long payoutId, String operatorId);

    Payout cancel(Long payoutId, String operatorId, String reason);
}
