package github.lms.lemuel.settlement.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface SpringDataSettlementAdjustmentJpaRepository
        extends JpaRepository<SettlementAdjustmentJpaEntity, Long> {

    List<SettlementAdjustmentJpaEntity> findByAdjustmentDateAndStatus(LocalDate date, String status);

    /** PG 대사 clawback 재전송 방어 — 같은 discrepancy 로 이미 조정 row 가 있으면 이중 회수 차단. */
    boolean existsByReconciliationDiscrepancyId(Long reconciliationDiscrepancyId);
}
