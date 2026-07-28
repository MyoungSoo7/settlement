package github.lms.lemuel.account.application.port.out;

/**
 * 셀러 단위 직렬화 락 아웃바운드 포트 — payout 의 무락 read-then-write 경합 방어(GL 감사 HIGH).
 *
 * <p>payout 이벤트는 payoutId 로 파티셔닝되고 컨슈머 concurrency 가 1 이 아니라, 같은 셀러의 동시 payout 2건이
 * 같은 SELLER_PAYABLE 잔액을 읽고 각각 full 상계 전기를 하면 통제계정이 음수로 몰리고 초과분 회수채권이
 * 인식되지 않는다(MED-3 가 막으려던 상태). 잔액 읽기 전에 셀러 단위 락을 잡아 같은 셀러 payout 을 직렬화한다.
 */
public interface AcquireSellerLockPort {

    /**
     * 현재 트랜잭션에 셀러 단위 payout 락을 건다(트랜잭션 종료 시 자동 해제). 같은 셀러의 다른 payout
     * 트랜잭션은 이 락이 풀릴 때까지 대기해 갱신된 잔액을 읽는다. 반드시 활성 트랜잭션 안에서 호출해야 한다.
     */
    void lockSellerForPayout(String sellerId);
}
