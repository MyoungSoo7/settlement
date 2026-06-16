package github.lms.lemuel.settlement.adapter.out.readmodel;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * settlement 소유 사용자 프로젝션 리포지토리 (ADR 0020 Phase 3b).
 * Phase 3b 컷오버에서 QueryDSL 이 email 을 이 프로젝션에서 읽는다.
 */
public interface SettlementUserViewRepository extends JpaRepository<SettlementUserViewJpaEntity, Long> {
}
