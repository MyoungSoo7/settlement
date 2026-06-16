package github.lms.lemuel.settlement.adapter.out.readmodel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * settlement 소유 결제 프로젝션 리포지토리 (ADR 0020 Phase 2).
 * Phase 3 에서 CapturedPaymentsAdapter 가 이 리포지토리로 컷오버된다.
 */
public interface SettlementPaymentViewRepository extends JpaRepository<SettlementPaymentViewJpaEntity, Long> {

    List<SettlementPaymentViewJpaEntity> findByCapturedAtBetweenAndStatus(
            LocalDateTime start, LocalDateTime end, String status);
}
