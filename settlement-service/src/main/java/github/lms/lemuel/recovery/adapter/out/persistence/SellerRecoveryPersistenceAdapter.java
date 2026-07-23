package github.lms.lemuel.recovery.adapter.out.persistence;

import github.lms.lemuel.recovery.application.port.out.LoadSellerRecoveryPort;
import github.lms.lemuel.recovery.application.port.out.RecoveryAllocationPort;
import github.lms.lemuel.recovery.application.port.out.SaveSellerRecoveryPort;
import github.lms.lemuel.recovery.domain.RecoveryAllocation;
import github.lms.lemuel.recovery.domain.RecoveryStatus;
import github.lms.lemuel.recovery.domain.SellerRecovery;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class SellerRecoveryPersistenceAdapter
        implements LoadSellerRecoveryPort, SaveSellerRecoveryPort, RecoveryAllocationPort {

    private final SpringDataSellerRecoveryJpaRepository recoveryRepository;
    private final SpringDataRecoveryAllocationJpaRepository allocationRepository;

    public SellerRecoveryPersistenceAdapter(SpringDataSellerRecoveryJpaRepository recoveryRepository,
                                            SpringDataRecoveryAllocationJpaRepository allocationRepository) {
        this.recoveryRepository = recoveryRepository;
        this.allocationRepository = allocationRepository;
    }

    @Override
    public Optional<SellerRecovery> findBySourceAdjustmentId(Long sourceAdjustmentId) {
        return recoveryRepository.findBySourceAdjustmentId(sourceAdjustmentId)
                .map(SellerRecoveryPersistenceAdapter::toDomain);
    }

    @Override
    public List<SellerRecovery> findOpenBySellerIdForUpdate(Long sellerId) {
        return recoveryRepository.findOpenBySellerIdForUpdate(sellerId).stream()
                .map(SellerRecoveryPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public List<SellerRecovery> findBySellerId(Long sellerId) {
        return recoveryRepository.findBySellerIdOrderByIdDesc(sellerId).stream()
                .map(SellerRecoveryPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public SellerRecovery save(SellerRecovery recovery) {
        SellerRecoveryJpaEntity entity;
        if (recovery.getId() == null) {
            entity = new SellerRecoveryJpaEntity(null, recovery.getSourceAdjustmentId(),
                    recovery.getSellerId(), recovery.getOriginalAmount(), recovery.getAllocatedAmount(),
                    recovery.getStatus().name(), recovery.getCreatedAt(), recovery.getClosedAt());
        } else {
            entity = recoveryRepository.findById(recovery.getId()).orElseThrow();
            entity.applyAllocationProgress(recovery.getAllocatedAmount(),
                    recovery.getStatus().name(), recovery.getClosedAt());
        }
        return toDomain(recoveryRepository.save(entity));
    }

    @Override
    public RecoveryAllocation save(RecoveryAllocation allocation) {
        RecoveryAllocationJpaEntity saved = allocationRepository.save(new RecoveryAllocationJpaEntity(
                allocation.id(), allocation.recoveryId(), allocation.settlementId(),
                allocation.amount(), allocation.createdAt()));
        return toDomain(saved);
    }

    @Override
    public java.math.BigDecimal sumBySettlementId(Long settlementId) {
        return allocationRepository.sumBySettlementId(settlementId);
    }

    @Override
    public List<RecoveryAllocation> findAllocationsBySellerId(Long sellerId) {
        return allocationRepository.findBySellerId(sellerId).stream()
                .map(SellerRecoveryPersistenceAdapter::toDomain)
                .toList();
    }

    private static SellerRecovery toDomain(SellerRecoveryJpaEntity entity) {
        return SellerRecovery.rehydrate(entity.getId(), entity.getSourceAdjustmentId(),
                entity.getSellerId(), entity.getOriginalAmount(), entity.getAllocatedAmount(),
                RecoveryStatus.valueOf(entity.getStatus()), entity.getCreatedAt(), entity.getClosedAt());
    }

    private static RecoveryAllocation toDomain(RecoveryAllocationJpaEntity entity) {
        return RecoveryAllocation.rehydrate(entity.getId(), entity.getRecoveryId(),
                entity.getSettlementId(), entity.getAmount(), entity.getCreatedAt());
    }
}
