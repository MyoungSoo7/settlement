package github.lms.lemuel.payout.application.service;

/**
 * 같은 Payout 을 다른 인스턴스/배치가 이미 선점(REQUESTED → SENDING)했을 때 발생.
 *
 * <p>실패가 아니라 정상적인 동시성 경합 — 외부 펌뱅킹 송금은 선점에 성공한 쪽만 1회 수행한다.
 * 진 쪽은 이 예외로 빠지고 해당 Payout 은 손대지 않은 채 다음 배치에서 다시 처리된다.
 */
public class PayoutConcurrentClaimException extends RuntimeException {

    public PayoutConcurrentClaimException(Long payoutId) {
        super("Payout already claimed by another executor: " + payoutId);
    }
}
