package github.lms.lemuel.payout.application.port.out;

import github.lms.lemuel.payout.domain.PayoutBounce;

public interface SavePayoutBouncePort {

    /**
     * 반송 저장. id 가 없으면 INSERT(payout_id UNIQUE 로 멱등 강제), 있으면 재발행 링크(resolved_payout_id)
     * 반영 UPDATE.
     */
    PayoutBounce save(PayoutBounce bounce);
}
