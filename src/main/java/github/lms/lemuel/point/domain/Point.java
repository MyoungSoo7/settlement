package github.lms.lemuel.point.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Point Domain Entity (순수 POJO)
 */
public class Point {

    private Long id;
    private Long userId;
    private BigDecimal balance;
    private BigDecimal totalEarned;
    private BigDecimal totalUsed;
    private List<PointTransaction> transactions;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Point() {
        this.balance = BigDecimal.ZERO;
        this.totalEarned = BigDecimal.ZERO;
        this.totalUsed = BigDecimal.ZERO;
        this.transactions = new ArrayList<>();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public static Point create(Long userId) {
        Point point = new Point();
        point.userId = userId;
        return point;
    }

    /**
     * 포인트 적립
     */
    public PointTransaction earn(BigDecimal amount, String description, String refType, Long refId) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("적립 금액은 0보다 커야 합니다.");
        }
        this.balance = this.balance.add(amount);
        this.totalEarned = this.totalEarned.add(amount);
        this.updatedAt = LocalDateTime.now();

        PointTransaction tx = PointTransaction.create(
                this.userId, this.id, PointTransactionType.EARN,
                amount, this.balance, description, refType, refId
        );
        this.transactions.add(tx);
        return tx;
    }

    /**
     * 포인트 사용
     */
    public PointTransaction use(BigDecimal amount, String description, String refType, Long refId) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("사용 금액은 0보다 커야 합니다.");
        }
        if (!hasSufficientBalance(amount)) {
            throw new IllegalStateException("포인트 잔액이 부족합니다. 현재 잔액: " + this.balance);
        }
        this.balance = this.balance.subtract(amount);
        this.totalUsed = this.totalUsed.add(amount);
        this.updatedAt = LocalDateTime.now();

        PointTransaction tx = PointTransaction.create(
                this.userId, this.id, PointTransactionType.USE,
                amount, this.balance, description, refType, refId
        );
        this.transactions.add(tx);
        return tx;
    }

    /**
     * 적립 취소
     */
    public PointTransaction cancelEarn(BigDecimal amount, String description, String refType, Long refId) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("취소 금액은 0보다 커야 합니다.");
        }
        if (this.balance.compareTo(amount) < 0) {
            throw new IllegalStateException("취소할 포인트가 현재 잔액보다 큽니다. 현재 잔액: " + this.balance);
        }
        this.balance = this.balance.subtract(amount);
        this.totalEarned = this.totalEarned.subtract(amount);
        this.updatedAt = LocalDateTime.now();

        PointTransaction tx = PointTransaction.create(
                this.userId, this.id, PointTransactionType.CANCEL_EARN,
                amount, this.balance, description, refType, refId
        );
        this.transactions.add(tx);
        return tx;
    }

    /**
     * 사용 취소 (포인트 환불)
     */
    public PointTransaction cancelUse(BigDecimal amount, String description, String refType, Long refId) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("취소 금액은 0보다 커야 합니다.");
        }
        this.balance = this.balance.add(amount);
        this.totalUsed = this.totalUsed.subtract(amount);
        this.updatedAt = LocalDateTime.now();

        PointTransaction tx = PointTransaction.create(
                this.userId, this.id, PointTransactionType.CANCEL_USE,
                amount, this.balance, description, refType, refId
        );
        this.transactions.add(tx);
        return tx;
    }

    /**
     * 관리자 조정
     */
    public PointTransaction adminAdjust(BigDecimal amount, String description) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException("조정 금액은 0이 아니어야 합니다.");
        }
        BigDecimal newBalance = this.balance.add(amount);
        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalStateException("조정 후 잔액이 음수가 됩니다. 현재 잔액: " + this.balance);
        }
        this.balance = newBalance;
        if (amount.compareTo(BigDecimal.ZERO) > 0) {
            this.totalEarned = this.totalEarned.add(amount);
        } else {
            this.totalUsed = this.totalUsed.add(amount.abs());
        }
        this.updatedAt = LocalDateTime.now();

        PointTransaction tx = PointTransaction.create(
                this.userId, this.id, PointTransactionType.ADMIN_ADJUST,
                amount, this.balance, description, null, null
        );
        this.transactions.add(tx);
        return tx;
    }

    public boolean hasSufficientBalance(BigDecimal amount) {
        return this.balance.compareTo(amount) >= 0;
    }

    public BigDecimal getAvailableBalance() {
        return this.balance;
    }

    // Getters & Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }

    public BigDecimal getTotalEarned() { return totalEarned; }
    public void setTotalEarned(BigDecimal totalEarned) { this.totalEarned = totalEarned; }

    public BigDecimal getTotalUsed() { return totalUsed; }
    public void setTotalUsed(BigDecimal totalUsed) { this.totalUsed = totalUsed; }

    public List<PointTransaction> getTransactions() { return transactions; }
    public void setTransactions(List<PointTransaction> transactions) { this.transactions = transactions; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
