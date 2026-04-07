package github.lms.lemuel.point.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * PointTransaction Domain Entity (순수 POJO)
 */
public class PointTransaction {

    private Long id;
    private Long userId;
    private Long pointId;
    private PointTransactionType type;
    private BigDecimal amount;
    private BigDecimal balanceAfter;
    private String description;
    private String referenceType;
    private Long referenceId;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;

    public PointTransaction() {
        this.createdAt = LocalDateTime.now();
    }

    public static PointTransaction create(Long userId, Long pointId, PointTransactionType type,
                                           BigDecimal amount, BigDecimal balanceAfter,
                                           String description, String referenceType, Long referenceId) {
        PointTransaction tx = new PointTransaction();
        tx.userId = userId;
        tx.pointId = pointId;
        tx.type = type;
        tx.amount = amount;
        tx.balanceAfter = balanceAfter;
        tx.description = description;
        tx.referenceType = referenceType;
        tx.referenceId = referenceId;
        return tx;
    }

    // Getters & Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getPointId() { return pointId; }
    public void setPointId(Long pointId) { this.pointId = pointId; }

    public PointTransactionType getType() { return type; }
    public void setType(PointTransactionType type) { this.type = type; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(BigDecimal balanceAfter) { this.balanceAfter = balanceAfter; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getReferenceType() { return referenceType; }
    public void setReferenceType(String referenceType) { this.referenceType = referenceType; }

    public Long getReferenceId() { return referenceId; }
    public void setReferenceId(Long referenceId) { this.referenceId = referenceId; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
