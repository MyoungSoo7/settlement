package github.lms.lemuel.deposit.adapter.out.persistence;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * deposit_accounts JPA 엔티티.
 *
 * <p>DB CHECK 제약(available>=0, locked>=0, total>=0, total=available+locked)이
 * 도메인 불변식의 최후 방어선이다 — Flyway V1 에서 생성된다.
 */
@Entity
@Table(
    name = "deposit_accounts",
    schema = "opslab",
    uniqueConstraints = @UniqueConstraint(name = "uq_deposit_accounts_seller_id", columnNames = "seller_id")
)
@EntityListeners(AuditingEntityListener.class)
public class DepositAccountJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "available", nullable = false, precision = 19, scale = 2)
    private BigDecimal available;

    @Column(name = "locked", nullable = false, precision = 19, scale = 2)
    private BigDecimal locked;

    @Column(name = "total", nullable = false, precision = 19, scale = 2)
    private BigDecimal total;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected DepositAccountJpaEntity() {}

    public DepositAccountJpaEntity(Long sellerId, BigDecimal available,
                                    BigDecimal locked, BigDecimal total) {
        this.sellerId = sellerId;
        this.available = available;
        this.locked = locked;
        this.total = total;
    }

    public Long getId() { return id; }
    public Long getSellerId() { return sellerId; }
    public BigDecimal getAvailable() { return available; }
    public BigDecimal getLocked() { return locked; }
    public BigDecimal getTotal() { return total; }
    public long getVersion() { return version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setAvailable(BigDecimal available) { this.available = available; }
    public void setLocked(BigDecimal locked) { this.locked = locked; }
    public void setTotal(BigDecimal total) { this.total = total; }
}
