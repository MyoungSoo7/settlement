package github.lms.lemuel.settlement.adapter.out.persistence;

import github.lms.lemuel.settlement.application.dto.SettlementBatchHealthSnapshot;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementBatchHealthPort;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Settlement Batch Health Persistence Adapter (Outbound)
 * 배치 헬스 체크를 위한 데이터 조회 어댑터
 */
@Component
public class SettlementBatchHealthPersistenceAdapter implements LoadSettlementBatchHealthPort {

    private final SpringDataSettlementJpaRepository settlementRepository;
    private final SpringDataSettlementAdjustmentJpaRepository adjustmentRepository;

    public SettlementBatchHealthPersistenceAdapter(SpringDataSettlementJpaRepository settlementRepository,
                                                     SpringDataSettlementAdjustmentJpaRepository adjustmentRepository) {
        this.settlementRepository = settlementRepository;
        this.adjustmentRepository = adjustmentRepository;
    }

    @Override
    public SettlementBatchHealthSnapshot loadHealthSnapshot(LocalDate date) {
        List<SettlementJpaEntity> settlements = settlementRepository.findBySettlementDate(date);

        long pendingCount = settlements.stream()
                .filter(s -> "PENDING".equals(s.getStatus()))
                .count();

        long confirmedCount = settlements.stream()
                .filter(s -> "CONFIRMED".equals(s.getStatus()))
                .count();

        // SettlementAdjustment 조회 (TODO 해결)
        long pendingAdjustmentCount = adjustmentRepository
                .findByAdjustmentDateAndStatus(date, "PENDING")
                .size();

        return new SettlementBatchHealthSnapshot(
                date,
                pendingCount,
                confirmedCount,
                pendingAdjustmentCount
        );
    }
}
