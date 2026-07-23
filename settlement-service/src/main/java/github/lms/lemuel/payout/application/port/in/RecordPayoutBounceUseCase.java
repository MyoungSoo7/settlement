package github.lms.lemuel.payout.application.port.in;

import github.lms.lemuel.payout.domain.Payout;
import github.lms.lemuel.payout.domain.PayoutBounce;

/**
 * 송금 반송(bounce) 기록 + 정정계좌 재지급 유스케이스.
 *
 * <p>실자금 경로. COMPLETED 송금이 은행단에서 반송되면 사유를 기록하고, 레지스트리에서 신선 로드한
 * 정정 계좌로 신규 payout 을 재발행한다. {@code payout_bounces.payout_id} UNIQUE 로 "반송당 정확히
 * 한 번"을 DB 가 강제하므로, 같은 반송을 두 번 기록해도 재지급은 정확히 한 건만 생성된다(이중지급 차단).
 */
public interface RecordPayoutBounceUseCase {

    /**
     * @param payoutId   반송된 원 송금(COMPLETED 여야 함)의 id
     * @param reason     반송 사유(필수 — 감사 추적)
     * @param operatorId 조작 운영자
     * @return 반송 레코드 + 재발행 payout(멱등 재호출 시 기존 값 그대로)
     */
    BounceOutcome recordBounce(Long payoutId, String reason, String operatorId);

    /** 반송 기록과 그로 인해 재발행된 payout. */
    record BounceOutcome(PayoutBounce bounce, Payout reissuedPayout) {
    }
}
