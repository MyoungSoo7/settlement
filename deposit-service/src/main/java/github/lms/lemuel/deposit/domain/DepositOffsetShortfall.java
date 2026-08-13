package github.lms.lemuel.deposit.domain;

import github.lms.lemuel.deposit.domain.exception.InvalidDepositStateException;
import github.lms.lemuel.deposit.domain.exception.DepositInvariantViolationException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 예치금 상계 부족분 (1급 도메인 레코드, 순수 POJO).
 *
 * <p>applyOffset 시 가용 재원이 요청액에 못 미칠 때 부족분을 영속화한다.
 * 상태 OPEN → RESOLVED | WRITTEN_OFF.
 * DepositShortfallRetryScheduler 가 주기적으로 OPEN 건을 재상계 시도한다.
 */
public class DepositOffsetShortfall {

    private Long id;
    private final Long sellerId;
    private final DepositHolderType holderType;
    private final String holderReference;
    private final BigDecimal requestedAmount;
    private BigDecimal appliedAmount;
    private BigDecimal shortfallAmount;
    private DepositShortfallStatus status;
    /** 상계에 사용된 hold ID (null = hold 없는 늦은 청구). */
    private final Long sourceHoldId;
    private final OffsetDateTime occurredAt;

    private DepositOffsetShortfall(Long id, Long sellerId,
                                    DepositHolderType holderType, String holderReference,
                                    BigDecimal requestedAmount, BigDecimal appliedAmount,
                                    BigDecimal shortfallAmount, DepositShortfallStatus status,
                                    Long sourceHoldId, OffsetDateTime occurredAt) {
        this.id = id;
        this.sellerId = Objects.requireNonNull(sellerId, "sellerId");
        this.holderType = Objects.requireNonNull(holderType, "holderType");
        this.holderReference = Objects.requireNonNull(holderReference, "holderReference");
        this.requestedAmount = Objects.requireNonNull(requestedAmount, "requestedAmount");
        this.appliedAmount = Objects.requireNonNull(appliedAmount, "appliedAmount");
        this.shortfallAmount = Objects.requireNonNull(shortfallAmount, "shortfallAmount");
        this.status = Objects.requireNonNull(status, "status");
        this.sourceHoldId = sourceHoldId;
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public static DepositOffsetShortfall open(Long sellerId,
                                               DepositHolderType holderType, String holderReference,
                                               BigDecimal requestedAmount, BigDecimal appliedAmount,
                                               Long sourceHoldId, OffsetDateTime occurredAt) {
        BigDecimal shortfall = requestedAmount.subtract(appliedAmount);
        return new DepositOffsetShortfall(null, sellerId, holderType, holderReference,
                requestedAmount, appliedAmount, shortfall,
                DepositShortfallStatus.OPEN, sourceHoldId, occurredAt);
    }

    public static DepositOffsetShortfall rehydrate(Long id, Long sellerId,
                                                    DepositHolderType holderType, String holderReference,
                                                    BigDecimal requestedAmount, BigDecimal appliedAmount,
                                                    BigDecimal shortfallAmount, DepositShortfallStatus status,
                                                    Long sourceHoldId, OffsetDateTime occurredAt) {
        return new DepositOffsetShortfall(id, sellerId, holderType, holderReference,
                requestedAmount, appliedAmount, shortfallAmount, status, sourceHoldId, occurredAt);
    }

    /** 부족분 재상계 성공 — RESOLVED 로 전이. */
    public void resolve(BigDecimal additionalApplied) {
        if (this.status != DepositShortfallStatus.OPEN) {
            throw new InvalidDepositStateException("OPEN 상태만 resolve 가능합니다. 현재=" + status, String.valueOf(status), "resolve");
        }
        this.appliedAmount = this.appliedAmount.add(additionalApplied);
        this.shortfallAmount = this.shortfallAmount.subtract(additionalApplied);
        this.status = DepositShortfallStatus.RESOLVED;
    }

    /** 수동 상각 — WRITTEN_OFF 로 전이. */
    public void writeOff() {
        if (this.status != DepositShortfallStatus.OPEN) {
            throw new InvalidDepositStateException("OPEN 상태만 write-off 가능합니다. 현재=" + status, String.valueOf(status), "writeOff");
        }
        this.status = DepositShortfallStatus.WRITTEN_OFF;
    }

    public void assignId(Long id) {
        if (this.id != null) throw new DepositInvariantViolationException("이미 ID 가 할당된 shortfall 입니다");
        this.id = Objects.requireNonNull(id);
    }

    public Long getId() { return id; }
    public Long getSellerId() { return sellerId; }
    public DepositHolderType getHolderType() { return holderType; }
    public String getHolderReference() { return holderReference; }
    public BigDecimal getRequestedAmount() { return requestedAmount; }
    public BigDecimal getAppliedAmount() { return appliedAmount; }
    public BigDecimal getShortfallAmount() { return shortfallAmount; }
    public DepositShortfallStatus getStatus() { return status; }
    public Long getSourceHoldId() { return sourceHoldId; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
}
