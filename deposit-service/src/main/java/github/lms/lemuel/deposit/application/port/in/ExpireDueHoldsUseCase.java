package github.lms.lemuel.deposit.application.port.in;

import java.time.LocalDateTime;

/**
 * 만료된 hold 회수 — 선점 시각이 지난 ACTIVE hold 를 EXPIRED 로 닫고 locked 를 available 로 되돌린다.
 *
 * <p>hold 를 걸 때 시스템이 스스로 {@code expiresAt} 을 기록해 두고 아무도 그것을 이행하지 않으면,
 * 만료된 선점이 locked 를 영구히 잡아 <b>가용액이 조용히 줄어든 채 유지</b>된다. 잔고가 틀리는 게
 * 아니라 *덜 보이는* 방향이라 잔고 검증(total = available + locked)으로는 잡히지 않는다 —
 * 셀러 입장에선 원인 없는 "왜 출금이 안 되지"가 된다. 이 유스케이스는 새 정책이 아니라
 * 이미 선언해 둔 만료를 이행하는 것이다.
 */
public interface ExpireDueHoldsUseCase {

    /**
     * {@code cutoff} 이전에 만료된 ACTIVE hold 를 전부 회수한다.
     *
     * <p>건별로 독립 처리한다 — 한 건이 실패해도 나머지를 회수한다. 전체를 한 트랜잭션에 묶으면
     * 계좌 하나의 락 경합이 그날의 회수 전체를 되돌린다.
     *
     * @return 실제로 EXPIRED 로 전이시킨 건수
     */
    int expireDueHolds(LocalDateTime cutoff);
}
