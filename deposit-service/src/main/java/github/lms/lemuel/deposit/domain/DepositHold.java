package github.lms.lemuel.deposit.domain;

import github.lms.lemuel.deposit.domain.exception.DepositInvariantViolationException;
import github.lms.lemuel.deposit.domain.exception.InvalidDepositAmountException;
import github.lms.lemuel.deposit.domain.exception.InvalidDepositStateException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 예치금 선점(Hold) 애그리거트 (순수 POJO — 프레임워크 의존 0).
 *
 * <p>(holderType, holderReference) 쌍이 자연키이자 멱등 키다.
 * 동일 키로 재요청 시 기존 hold 를 그대로 반환해야 한다(DB UNIQUE 2차 방어).
 *
 * <p>상태머신: ACTIVE → PARTIALLY_CAPTURED → CAPTURED
 *                         ↓
 *                      EXPIRED / VOIDED / RELEASED
 */
public class DepositHold {

    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private Long id;
    private final Long accountId;
    private final DepositHolderType holderType;
    private final String holderReference;
    private final BigDecimal originalAmount;
    private BigDecimal remainingAmount;
    private DepositHoldStatus status;
    private final LocalDateTime expiresAt;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private long version;

    private DepositHold(Long id, Long accountId,
                         DepositHolderType holderType, String holderReference,
                         BigDecimal originalAmount, BigDecimal remainingAmount,
                         DepositHoldStatus status, LocalDateTime expiresAt,
                         LocalDateTime createdAt, LocalDateTime updatedAt, long version) {
        this.id = id;
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.holderType = Objects.requireNonNull(holderType, "holderType");
        this.holderReference = Objects.requireNonNull(holderReference, "holderReference");
        this.originalAmount = norm(Objects.requireNonNull(originalAmount, "originalAmount"));
        this.remainingAmount = norm(Objects.requireNonNull(remainingAmount, "remainingAmount"));
        this.status = Objects.requireNonNull(status, "status");
        this.expiresAt = expiresAt;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.updatedAt = updatedAt != null ? updatedAt : LocalDateTime.now();
        this.version = version;
    }

    public static DepositHold place(Long accountId, DepositHolderType holderType,
                                     String holderReference, BigDecimal amount,
                                     LocalDateTime expiresAt) {
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidDepositAmountException("hold 금액은 양수여야 합니다: " + amount, "place", amount);
        }
        LocalDateTime now = LocalDateTime.now();
        return new DepositHold(null, accountId, holderType, holderReference,
                amount, amount, DepositHoldStatus.ACTIVE, expiresAt, now, now, 0L);
    }

    public static DepositHold rehydrate(Long id, Long accountId,
                                         DepositHolderType holderType, String holderReference,
                                         BigDecimal originalAmount, BigDecimal remainingAmount,
                                         DepositHoldStatus status, LocalDateTime expiresAt,
                                         LocalDateTime createdAt, LocalDateTime updatedAt, long version) {
        return new DepositHold(id, accountId, holderType, holderReference,
                originalAmount, remainingAmount, status, expiresAt, createdAt, updatedAt, version);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 상태 전이
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 캡처(부분 또는 전액) — remainingAmount 에서 captureAmount 차감.
     *
     * @return 캡처 후 잔여 remaining (> 0 이면 PARTIALLY_CAPTURED, == 0 이면 CAPTURED)
     */
    public BigDecimal capture(BigDecimal captureAmount) {
        requireCapturable();
        if (captureAmount == null || captureAmount.signum() <= 0) {
            throw new InvalidDepositAmountException("캡처 금액은 양수여야 합니다: " + captureAmount, "capture", captureAmount);
        }
        BigDecimal normCapture = norm(captureAmount);
        if (normCapture.compareTo(this.remainingAmount) > 0) {
            throw new InvalidDepositAmountException(
                    "캡처 금액(" + normCapture + ")이 remaining(" + this.remainingAmount + ")을 초과합니다",
                    "capture", normCapture);
        }
        this.remainingAmount = this.remainingAmount.subtract(normCapture);
        this.status = this.remainingAmount.signum() == 0
                ? DepositHoldStatus.CAPTURED
                : DepositHoldStatus.PARTIALLY_CAPTURED;
        touch();
        return this.remainingAmount;
    }

    /**
     * 만료 — 배치 스케줄러가 TTL 초과 ACTIVE hold 에 호출.
     */
    public void expire() {
        if (this.status != DepositHoldStatus.ACTIVE) {
            throw new InvalidDepositStateException("ACTIVE 상태만 만료 가능합니다. 현재=" + status,
                    String.valueOf(status), "expire");
        }
        this.status = DepositHoldStatus.EXPIRED;
        touch();
    }

    /**
     * 취소 — 명시적 void.
     */
    public void voidHold() {
        if (this.status != DepositHoldStatus.ACTIVE
                && this.status != DepositHoldStatus.PARTIALLY_CAPTURED) {
            throw new InvalidDepositStateException(
                    "ACTIVE 또는 PARTIALLY_CAPTURED 상태만 취소 가능합니다. 현재=" + status,
                    String.valueOf(status), "voidHold");
        }
        this.status = DepositHoldStatus.VOIDED;
        touch();
    }

    /**
     * 잔여 해제 — 캡처 이후 남은 remaining 을 release.
     */
    public void release() {
        if (this.status != DepositHoldStatus.ACTIVE
                && this.status != DepositHoldStatus.PARTIALLY_CAPTURED) {
            throw new InvalidDepositStateException(
                    "ACTIVE 또는 PARTIALLY_CAPTURED 상태만 해제 가능합니다. 현재=" + status,
                    String.valueOf(status), "release");
        }
        this.status = DepositHoldStatus.RELEASED;
        touch();
    }

    public boolean isActive() {
        return status == DepositHoldStatus.ACTIVE
                || status == DepositHoldStatus.PARTIALLY_CAPTURED;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 내부 헬퍼
    // ──────────────────────────────────────────────────────────────────────────

    private void requireCapturable() {
        if (this.status != DepositHoldStatus.ACTIVE
                && this.status != DepositHoldStatus.PARTIALLY_CAPTURED) {
            throw new InvalidDepositStateException(
                    "ACTIVE 또는 PARTIALLY_CAPTURED hold 만 캡처 가능합니다. 현재=" + status,
                    String.valueOf(status), "capture");
        }
    }

    private static BigDecimal norm(BigDecimal v) {
        return v.setScale(SCALE, ROUNDING);
    }

    private void touch() { this.updatedAt = LocalDateTime.now(); }

    // ──────────────────────────────────────────────────────────────────────────
    // Getters
    // ──────────────────────────────────────────────────────────────────────────

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public DepositHolderType getHolderType() { return holderType; }
    public String getHolderReference() { return holderReference; }
    public BigDecimal getOriginalAmount() { return originalAmount; }
    public BigDecimal getRemainingAmount() { return remainingAmount; }
    public DepositHoldStatus getStatus() { return status; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }

    public void assignId(Long id) {
        if (this.id != null) throw new DepositInvariantViolationException("이미 ID 가 할당된 hold 입니다");
        this.id = Objects.requireNonNull(id);
    }
}
