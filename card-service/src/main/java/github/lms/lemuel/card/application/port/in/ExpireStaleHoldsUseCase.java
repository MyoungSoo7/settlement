package github.lms.lemuel.card.application.port.in;

/**
 * 미매입 홀드 만료 유스케이스 포트.
 *
 * <p>일 1회 배치({@code HoldExpiryScheduler})가 호출한다.
 * {@code expiryDays} 이상 ACTIVE 상태로 남아 있는 홀드를 EXPIRED 로 전환해 가용한도를 복구한다.
 *
 * <p>만료된 홀드는 한도 합계에서 제외된다 — VAN 네트워크가 매입 확정을 늦게 보내거나
 * 누락한 경우 한도가 영원히 차감된 채 남는 것을 방지한다.
 */
public interface ExpireStaleHoldsUseCase {

    /**
     * 만료 배치 실행.
     *
     * @param expiryDays 이 일 수 이상 ACTIVE 인 홀드를 만료시킨다
     * @return 만료 처리된 홀드 수
     */
    int expireStaleHolds(int expiryDays);
}
