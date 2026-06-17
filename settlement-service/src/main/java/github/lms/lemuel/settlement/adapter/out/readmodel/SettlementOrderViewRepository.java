package github.lms.lemuel.settlement.adapter.out.readmodel;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;

/**
 * settlement 소유 주문 프로젝션 리포지토리 (ADR 0020 Phase 3b).
 * Phase 3b 컷오버에서 QueryDSL/ES 가 order 데이터를 이 프로젝션에서 읽는다.
 */
public interface SettlementOrderViewRepository extends JpaRepository<SettlementOrderViewJpaEntity, Long> {

    /** 전체 주문 프로젝션 금액 합계 — cross-DB 금액 대사(Phase 5.2). order sumAmount() 와 정합. */
    @Query("SELECT COALESCE(SUM(v.amount), 0) FROM SettlementOrderViewJpaEntity v")
    BigDecimal sumAmount();
}
