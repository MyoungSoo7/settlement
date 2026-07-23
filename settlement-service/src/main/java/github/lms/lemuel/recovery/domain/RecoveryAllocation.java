package github.lms.lemuel.recovery.domain;

import github.lms.lemuel.recovery.domain.exception.RecoveryInvariantViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 상계 이력 — (채권, 후속 정산) 축의 append-only 배분 레코드. 수정·삭제 없음(DB 트리거로도 강제).
 *
 * <p>지급(Payout) 축은 보관하지 않는다 — 상계 후 잔여 지급은 (settlement, IMMEDIATE) UNIQUE 로
 * 역추적 가능하고, payout 은 상계 이후에 생성되므로 여기 담으면 append-only 와 모순된다.
 */
public record RecoveryAllocation(Long id, Long recoveryId, Long settlementId,
                                 BigDecimal amount, LocalDateTime createdAt) {

    public RecoveryAllocation {
        if (recoveryId == null) {
            throw new RecoveryInvariantViolationException("recoveryId 필수");
        }
        if (settlementId == null) {
            throw new RecoveryInvariantViolationException("settlementId 필수");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new RecoveryInvariantViolationException("상계 금액은 양수여야 합니다: " + amount);
        }
    }

    public static RecoveryAllocation allocationOf(Long recoveryId, Long settlementId, BigDecimal amount) {
        return new RecoveryAllocation(null, recoveryId, settlementId, amount, LocalDateTime.now());
    }

    public static RecoveryAllocation rehydrate(Long id, Long recoveryId, Long settlementId,
                                               BigDecimal amount, LocalDateTime createdAt) {
        return new RecoveryAllocation(id, recoveryId, settlementId, amount, createdAt);
    }
}
