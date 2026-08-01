package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.CardAccount;

import java.util.List;
import java.util.Optional;

/**
 * 카드계정 조회 포트.
 */
public interface LoadCardAccountPort {

    Optional<CardAccount> findById(Long id);

    Optional<CardAccount> findByOrganizationId(Long organizationId);

    /**
     * 비관적 락 조회 — 카드 발급·한도 변경처럼 {@code masterLimit >= Σ subLimit} 불변식을
     * 재계산해야 하는 경로는 반드시 이걸로 계정 행을 잠근 뒤 합계를 읽는다.
     * 호출자는 활성 트랜잭션 안이어야 한다.
     */
    Optional<CardAccount> findByIdForUpdate(Long id);

    /** 일 1회 한도 재산정 스케줄러(Task 13)가 훑는 ACTIVE 계정 전체. */
    List<CardAccount> findAllActive();
}
