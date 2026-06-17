package github.lms.lemuel.settlement.adapter.out.readmodel;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * settlement 소유 결제 프로젝션 리포지토리 (ADR 0020 Phase 2).
 * Phase 3 에서 CapturedPaymentsAdapter 가 이 리포지토리로 컷오버된다.
 */
public interface SettlementPaymentViewRepository extends JpaRepository<SettlementPaymentViewJpaEntity, Long> {

    List<SettlementPaymentViewJpaEntity> findByCapturedAtBetweenAndStatus(
            LocalDateTime start, LocalDateTime end, String status);

    /** CAPTURED 프로젝션 건수 — cross-DB 대사(Phase 5.2). order countByStatus('CAPTURED') 와 정합. */
    long countByStatus(String status);

    /** CAPTURED 프로젝션 금액 합계 — cross-DB 금액 대사(Phase 5.2). order sumCapturedAmount() 와 정합. */
    @Query("SELECT COALESCE(SUM(v.amount), 0) FROM SettlementPaymentViewJpaEntity v WHERE v.status = 'CAPTURED'")
    BigDecimal sumCapturedAmount();
}
