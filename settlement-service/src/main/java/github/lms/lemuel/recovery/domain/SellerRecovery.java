package github.lms.lemuel.recovery.domain;

import github.lms.lemuel.recovery.domain.exception.InvalidRecoveryStateException;
import github.lms.lemuel.recovery.domain.exception.RecoveryInvariantViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 지급후 회수 채권 애그리거트 (시드 P0-6).
 *
 * <p>COMPLETED Payout 정산에 차지백·PG 대사 회수가 발생하면, holdback 으로 흡수되지 못한
 * 잔여분이 채권 원금이 된다. 원금({@code originalAmount})은 발생 후 불변이고, 상계는
 * {@link #allocate} 누적({@code allocatedAmount})으로만 진행된다 — 잔액 검증·CLOSED 전이를
 * 도메인이 소유한다(서비스단 sum 쿼리 검증 금지).
 *
 * <p>생성은 {@link #open}(발생)·{@link #rehydrate}(영속 복원) 팩토리 전용 — public 생성자·setter 없음.
 */
public class SellerRecovery {

    private final Long id;
    private final Long sourceAdjustmentId;
    private final Long sellerId;
    private final BigDecimal originalAmount;
    private BigDecimal allocatedAmount;
    private RecoveryStatus status;
    private final LocalDateTime createdAt;
    private LocalDateTime closedAt;

    private SellerRecovery(Long id, Long sourceAdjustmentId, Long sellerId,
                           BigDecimal originalAmount, BigDecimal allocatedAmount,
                           RecoveryStatus status, LocalDateTime createdAt, LocalDateTime closedAt) {
        if (sourceAdjustmentId == null) {
            throw new RecoveryInvariantViolationException("sourceAdjustmentId 필수");
        }
        if (sellerId == null) {
            throw new RecoveryInvariantViolationException("sellerId 필수");
        }
        if (originalAmount == null || originalAmount.signum() <= 0) {
            throw new RecoveryInvariantViolationException("채권 원금은 양수여야 합니다: " + originalAmount);
        }
        if (allocatedAmount == null || allocatedAmount.signum() < 0
                || allocatedAmount.compareTo(originalAmount) > 0) {
            throw new RecoveryInvariantViolationException(
                    "상계 누적은 0 이상, 원금 이하여야 합니다: " + allocatedAmount);
        }
        this.id = id;
        this.sourceAdjustmentId = sourceAdjustmentId;
        this.sellerId = sellerId;
        this.originalAmount = originalAmount;
        this.allocatedAmount = allocatedAmount;
        this.status = status;
        this.createdAt = createdAt;
        this.closedAt = closedAt;
    }

    /** 채권 발생 — holdback 흡수 후 잔여분을 원금으로 OPEN 상태로 연다. */
    public static SellerRecovery open(Long sourceAdjustmentId, Long sellerId, BigDecimal originalAmount) {
        return new SellerRecovery(null, sourceAdjustmentId, sellerId, originalAmount,
                BigDecimal.ZERO, RecoveryStatus.OPEN, LocalDateTime.now(), null);
    }

    /** 영속 복원 전용 — 검증은 하되 상태를 그대로 보존한다. */
    public static SellerRecovery rehydrate(Long id, Long sourceAdjustmentId, Long sellerId,
                                           BigDecimal originalAmount, BigDecimal allocatedAmount,
                                           RecoveryStatus status, LocalDateTime createdAt,
                                           LocalDateTime closedAt) {
        return new SellerRecovery(id, sourceAdjustmentId, sellerId, originalAmount, allocatedAmount,
                status, createdAt, closedAt);
    }

    /**
     * 후속 정산에서 상계를 시도한다 — 잔액을 넘는 요청은 잔액만큼만 소비한다
     * (초과분은 호출자가 같은 셀러의 다음 채권으로 넘긴다).
     *
     * @return 실제 상계된 금액 (양수, 잔액 상한)
     */
    public BigDecimal allocate(BigDecimal requested) {
        if (status != RecoveryStatus.OPEN) {
            throw new InvalidRecoveryStateException(status, "allocate");
        }
        if (requested == null || requested.signum() <= 0) {
            throw new RecoveryInvariantViolationException("상계 요청은 양수여야 합니다: " + requested);
        }
        BigDecimal consumed = outstanding().min(requested);
        this.allocatedAmount = this.allocatedAmount.add(consumed);
        if (outstanding().signum() == 0) {
            this.status = RecoveryStatus.CLOSED;
            this.closedAt = LocalDateTime.now();
        }
        return consumed;
    }

    /** 자동 상계 불가 판단 시 수기 회수로 이관한다 (단방향). */
    public void markManualRequired() {
        if (!status.canTransitionTo(RecoveryStatus.MANUAL_REQUIRED)) {
            throw new InvalidRecoveryStateException(status, "markManualRequired");
        }
        this.status = RecoveryStatus.MANUAL_REQUIRED;
    }

    /** 미상계 잔액 = 원금 − 상계 누적. */
    public BigDecimal outstanding() {
        return originalAmount.subtract(allocatedAmount);
    }

    public Long getId() {
        return id;
    }

    public Long getSourceAdjustmentId() {
        return sourceAdjustmentId;
    }

    public Long getSellerId() {
        return sellerId;
    }

    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public BigDecimal getAllocatedAmount() {
        return allocatedAmount;
    }

    public RecoveryStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }
}
