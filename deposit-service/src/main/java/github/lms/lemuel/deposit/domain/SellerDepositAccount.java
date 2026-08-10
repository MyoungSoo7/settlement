package github.lms.lemuel.deposit.domain;

import github.lms.lemuel.deposit.domain.exception.InsufficientDepositException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 셀러 예치 계좌 애그리거트 (순수 POJO — 프레임워크 의존 0).
 *
 * <h3>잔고 불변식 (항상 성립해야 함)</h3>
 * <ul>
 *   <li>{@code total = available + locked}
 *   <li>{@code available >= 0}
 *   <li>{@code locked >= 0}
 *   <li>{@code total >= 0}
 * </ul>
 *
 * <p>모든 상태 변경 메서드는 사전에 인수 유효성을 검사하고,
 * 잔고 불변식을 위반하는 상태로 전이를 거부한다(예외를 던진다).
 * JPA 엔티티의 DB CHECK 제약이 최후 방어선이다.
 *
 * <h3>동시성</h3>
 * 영속 어댑터가 비관적 락({@code SELECT ... FOR UPDATE}) 또는 낙관적 버전으로
 * 직렬화하므로, 동일 계좌의 동시 write 는 반드시 하나씩 처리된다.
 * version 필드는 낙관적 락에 사용된다.
 */
public class SellerDepositAccount {

    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private Long id;
    private final Long sellerId;
    private BigDecimal available;
    private BigDecimal locked;
    private BigDecimal total;
    /** 낙관적 락 버전 (영속 어댑터가 @Version 로 관리). */
    private long version;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private SellerDepositAccount(Long id, Long sellerId,
                                  BigDecimal available, BigDecimal locked, BigDecimal total,
                                  long version, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.sellerId = Objects.requireNonNull(sellerId, "sellerId");
        this.available = normalize(available);
        this.locked = normalize(locked);
        this.total = normalize(total);
        this.version = version;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.updatedAt = updatedAt != null ? updatedAt : LocalDateTime.now();
        enforceInvariant();
    }

    /**
     * 신규 계좌 생성 — 모든 잔고 0.
     */
    public static SellerDepositAccount open(Long sellerId) {
        LocalDateTime now = LocalDateTime.now();
        return new SellerDepositAccount(
                null, sellerId,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                0L, now, now);
    }

    /**
     * 영속 상태에서 재구성 (어댑터 전용).
     */
    public static SellerDepositAccount rehydrate(Long id, Long sellerId,
                                                   BigDecimal available, BigDecimal locked, BigDecimal total,
                                                   long version, LocalDateTime createdAt, LocalDateTime updatedAt) {
        return new SellerDepositAccount(id, sellerId, available, locked, total,
                version, createdAt, updatedAt);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 상태 변경 메서드
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 입금 (정산 확정 등) — available 증가.
     *
     * @param amount 양수여야 함
     */
    public void credit(BigDecimal amount) {
        requirePositive(amount, "credit");
        this.available = this.available.add(normalize(amount));
        this.total = this.total.add(normalize(amount));
        touch();
        enforceInvariant();
    }

    /**
     * 출금 (payout 실행 등) — available 감소.
     *
     * @param amount 양수여야 하며, available 이상이어야 함
     * @throws InsufficientDepositException available < amount
     */
    public void debit(BigDecimal amount) {
        requirePositive(amount, "debit");
        BigDecimal norm = normalize(amount);
        if (this.available.compareTo(norm) < 0) {
            throw new InsufficientDepositException(
                    "available(" + this.available + ") < debit(" + norm + ")",
                    String.valueOf(sellerId), "debit");
        }
        this.available = this.available.subtract(norm);
        this.total = this.total.subtract(norm);
        touch();
        enforceInvariant();
    }

    /**
     * 잠금 (hold 설정) — available → locked 이동.
     *
     * @param amount 양수여야 하며, available 이상이어야 함
     * @throws InsufficientDepositException available < amount
     */
    public void lock(BigDecimal amount) {
        requirePositive(amount, "lock");
        BigDecimal norm = normalize(amount);
        if (this.available.compareTo(norm) < 0) {
            throw new InsufficientDepositException(
                    "available(" + this.available + ") < lock(" + norm + ")",
                    String.valueOf(sellerId), "lock");
        }
        this.available = this.available.subtract(norm);
        this.locked = this.locked.add(norm);
        touch();
        enforceInvariant();
    }

    /**
     * 잠금 해제 (hold 만료·취소) — locked → available 이동.
     *
     * @param amount 양수여야 하며, locked 이상이어야 함
     * @throws InsufficientDepositException locked < amount
     */
    public void release(BigDecimal amount) {
        requirePositive(amount, "release");
        BigDecimal norm = normalize(amount);
        if (this.locked.compareTo(norm) < 0) {
            throw new InsufficientDepositException(
                    "locked(" + this.locked + ") < release(" + norm + ")",
                    String.valueOf(sellerId), "release");
        }
        this.locked = this.locked.subtract(norm);
        this.available = this.available.add(norm);
        touch();
        enforceInvariant();
    }

    /**
     * hold 에서 상계(capture) — locked 직접 차감, total 감소.
     * available 은 변하지 않는다.
     *
     * <p>hold 가 선점했던 locked 금액에서 실제 청구액을 차감한다.
     * 잔여 locked 는 호출 이후 {@link #release} 로 available 에 반환해야 한다.
     *
     * @param amount 양수여야 하며, locked 이상이어야 함
     * @throws InsufficientDepositException locked < amount
     */
    public void captureFromLocked(BigDecimal amount) {
        requirePositive(amount, "captureFromLocked");
        BigDecimal norm = normalize(amount);
        if (this.locked.compareTo(norm) < 0) {
            throw new InsufficientDepositException(
                    "locked(" + this.locked + ") < captureFromLocked(" + norm + ")",
                    String.valueOf(sellerId), "captureFromLocked");
        }
        this.locked = this.locked.subtract(norm);
        this.total = this.total.subtract(norm);
        touch();
        enforceInvariant();
    }

    /**
     * available 에서 직접 상계 — hold 없는 늦은 청구 경로.
     * locked 는 변하지 않는다.
     *
     * @param amount 양수여야 하며, available 이상이어야 함
     * @throws InsufficientDepositException available < amount
     */
    public void debitAvailable(BigDecimal amount) {
        requirePositive(amount, "debitAvailable");
        BigDecimal norm = normalize(amount);
        if (this.available.compareTo(norm) < 0) {
            throw new InsufficientDepositException(
                    "available(" + this.available + ") < debitAvailable(" + norm + ")",
                    String.valueOf(sellerId), "debitAvailable");
        }
        this.available = this.available.subtract(norm);
        this.total = this.total.subtract(norm);
        touch();
        enforceInvariant();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 내부 헬퍼
    // ──────────────────────────────────────────────────────────────────────────

    private static BigDecimal normalize(BigDecimal value) {
        if (value == null) throw new IllegalArgumentException("금액은 null 일 수 없습니다");
        return value.setScale(SCALE, ROUNDING);
    }

    private static void requirePositive(BigDecimal amount, String op) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException(op + " 금액은 양수여야 합니다: " + amount);
        }
    }

    /**
     * 도메인 불변식 검증 — 모든 상태 변경 이후 호출된다.
     * 이 메서드가 예외를 던지면 로직 버그이므로, 절대 외부에서 catch 해서 넘어가면 안 된다.
     */
    private void enforceInvariant() {
        if (available.signum() < 0) {
            throw new IllegalStateException("불변식 위반: available < 0 (" + available + ")");
        }
        if (locked.signum() < 0) {
            throw new IllegalStateException("불변식 위반: locked < 0 (" + locked + ")");
        }
        if (total.signum() < 0) {
            throw new IllegalStateException("불변식 위반: total < 0 (" + total + ")");
        }
        BigDecimal expectedTotal = available.add(locked);
        if (total.compareTo(expectedTotal) != 0) {
            throw new IllegalStateException(
                    "불변식 위반: total(" + total + ") != available(" + available
                            + ") + locked(" + locked + ") = " + expectedTotal);
        }
    }

    private void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Getters
    // ──────────────────────────────────────────────────────────────────────────

    public Long getId() { return id; }
    public Long getSellerId() { return sellerId; }
    public BigDecimal getAvailable() { return available; }
    public BigDecimal getLocked() { return locked; }
    public BigDecimal getTotal() { return total; }
    public long getVersion() { return version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    /** 영속 어댑터가 저장 후 할당된 ID 를 역주입하는 전용 메서드. */
    public void assignId(Long id) {
        if (this.id != null) {
            throw new IllegalStateException("이미 ID 가 할당된 계좌입니다: " + this.id);
        }
        this.id = Objects.requireNonNull(id, "id");
    }
}
