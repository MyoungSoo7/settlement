package github.lms.lemuel.payout.application.port.out;

import github.lms.lemuel.payout.domain.Payout;

import java.util.Optional;

public interface SavePayoutPort {
    Payout save(Payout payout);

    /**
     * 원자적 선점: REQUESTED → SENDING 으로 상태를 바꾸고, 성공 시 SENDING 상태의 Payout 을 반환한다.
     * 외부 송금 직전 동시성 가드. 이미 다른 인스턴스가 선점했으면 {@link Optional#empty()}.
     */
    Optional<Payout> claimForSending(Long payoutId);
}
