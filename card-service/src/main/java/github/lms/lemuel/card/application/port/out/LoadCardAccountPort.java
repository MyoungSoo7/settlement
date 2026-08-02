package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.CardAccount;

import java.util.List;
import java.util.Optional;

/**
 * 카드계정 조회 포트. organization-service {@code LoadOrganizationPort} 와 동형 —
 * 구현은 {@code card.adapter.out.persistence} 에만 있다(application 은 adapter 를 모른다).
 */
public interface LoadCardAccountPort {

    /** 조직당 카드계정 1개(uq_card_account_org) — 개설 여부·중복 개설 검증에 쓴다. */
    Optional<CardAccount> findByOrganizationId(Long organizationId);

    /** 단순 조회 — 락 없음. */
    Optional<CardAccount> findById(Long id);

    /**
     * 비관적 락(PESSIMISTIC_WRITE)으로 카드계정 행을 잠근다.
     *
     * <p>master_limit &gt;= Σ subLimit 불변식은 DB 집계 제약으로 표현할 수 없어(Postgres 는
     * CHECK 절에서 다른 테이블을 집계할 수 없다), 발급·한도변경 유스케이스가 이 락을 잡은 채로
     * {@link LoadCardPort#sumActiveSubLimits(Long)} 를 재계산해 검증해야 동시 요청 경쟁에서
     * 불변식이 깨지지 않는다. Task 10 의 동시 발급 불변식 테스트가 이 락에 전적으로 의존한다.
     * 호출자는 반드시 활성 트랜잭션 안에서 호출해야 락이 유지된다.
     */
    Optional<CardAccount> findByIdForUpdate(Long id);

    /** status=ACTIVE 인 카드계정 전체 — Task 13 일 1회 한도 재산정 스케줄러 전용. */
    List<CardAccount> findAllActive();
}
