package github.lms.lemuel.account.adapter.out.persistence;

import github.lms.lemuel.account.application.port.out.AcquireSellerLockPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

/**
 * PG advisory 트랜잭션 락으로 셀러 단위 payout 을 직렬화한다(GL 감사 HIGH — 무락 read-then-write 방어).
 *
 * <p>2-키 형식 {@code pg_advisory_xact_lock(ns, key)} 을 쓴다 — 첫 키는 payout 전용 네임스페이스
 * ({@code hashtext('account:payout')})라 다른 피처의 advisory 락과 키 공간이 겹치지 않고, 둘째 키는
 * {@code hashtext(sellerId)} 라 셀러별로 갈린다(다른 셀러 payout 은 서로 대기하지 않음). xact 락이라
 * 호출 트랜잭션이 커밋/롤백될 때 자동 해제된다.
 */
@Component
public class SellerAdvisoryLockAdapter implements AcquireSellerLockPort {

    /** payout 전용 락 네임스페이스 — hashtext 로 int4 파생해 advisory 락 첫 키로 쓴다(refType/토픽과 무관). */
    static final String LOCK_NAMESPACE = "account:payout";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void lockSellerForPayout(String sellerId) {
        // ::text 캐스트로 void 반환을 명시적 텍스트('')로 만들어 결과 매핑을 안정화한다(락 획득은 부작용).
        entityManager.createNativeQuery(
                        "SELECT pg_advisory_xact_lock(hashtext(:ns), hashtext(:sellerId))::text")
                .setParameter("ns", LOCK_NAMESPACE)
                .setParameter("sellerId", sellerId)
                .getSingleResult();
    }
}
