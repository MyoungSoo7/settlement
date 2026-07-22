package github.lms.lemuel.recovery.application.service;

import github.lms.lemuel.ledger.application.port.in.RecoveryEntryUseCase;
import github.lms.lemuel.recovery.application.port.in.OffsetSellerRecoveryUseCase;
import github.lms.lemuel.recovery.application.port.out.LoadSellerRecoveryPort;
import github.lms.lemuel.recovery.application.port.out.RecoveryAllocationPort;
import github.lms.lemuel.recovery.application.port.out.SaveSellerRecoveryPort;
import github.lms.lemuel.recovery.domain.RecoveryAllocation;
import github.lms.lemuel.recovery.domain.SellerRecovery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 후속 정산 확정 시 채권 상계 (seed-p0-6) — 확정 청크 트랜잭션에 합류한다.
 *
 * <p>셀러의 OPEN 채권을 오래된 순으로 비관락 스캔하며 소진한다. 잔액 검증·CLOSED 전이는
 * {@link SellerRecovery#allocate} 도메인이 소유하고, 여기는 순회·이력·분개만 오케스트레이션한다.
 * 재실행은 (recovery, settlement) UNIQUE + 기존 상계 총액 재사용으로 멱등.
 */
@Slf4j
@Service
public class OffsetSellerRecoveryService implements OffsetSellerRecoveryUseCase {

    private final LoadSellerRecoveryPort loadRecoveryPort;
    private final SaveSellerRecoveryPort saveRecoveryPort;
    private final RecoveryAllocationPort allocationPort;
    private final RecoveryEntryUseCase recoveryEntryUseCase;

    public OffsetSellerRecoveryService(LoadSellerRecoveryPort loadRecoveryPort,
                                       SaveSellerRecoveryPort saveRecoveryPort,
                                       RecoveryAllocationPort allocationPort,
                                       RecoveryEntryUseCase recoveryEntryUseCase) {
        this.loadRecoveryPort = loadRecoveryPort;
        this.saveRecoveryPort = saveRecoveryPort;
        this.allocationPort = allocationPort;
        this.recoveryEntryUseCase = recoveryEntryUseCase;
    }

    @Override
    @Transactional
    public BigDecimal offsetForConfirmedSettlement(Long settlementId, Long sellerId,
                                                   BigDecimal immediateAmount, LocalDate settlementDate) {
        if (immediateAmount == null || immediateAmount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal prior = allocationPort.sumBySettlementId(settlementId);
        if (prior.signum() > 0) {
            log.info("[Recovery] 상계 재실행 감지 — 기존 상계 총액 재사용. settlementId={}, prior={}",
                    settlementId, prior);
            return prior;
        }

        BigDecimal remaining = immediateAmount;
        BigDecimal total = BigDecimal.ZERO;
        for (SellerRecovery recovery : loadRecoveryPort.findOpenBySellerIdForUpdate(sellerId)) {
            BigDecimal consumed = recovery.allocate(remaining);
            saveRecoveryPort.save(recovery);
            RecoveryAllocation allocation = allocationPort.save(
                    RecoveryAllocation.allocationOf(recovery.getId(), settlementId, consumed));
            recoveryEntryUseCase.offsetReceivable(allocation.id(), recovery.getId(), consumed, settlementDate);
            remaining = remaining.subtract(consumed);
            total = total.add(consumed);
            if (remaining.signum() == 0) {
                break;
            }
        }
        if (total.signum() > 0) {
            log.warn("[Recovery] 채권 상계 적용. settlementId={}, sellerId={}, offset={}, 남은지급={}",
                    settlementId, sellerId, total, remaining);
        }
        return total;
    }
}
