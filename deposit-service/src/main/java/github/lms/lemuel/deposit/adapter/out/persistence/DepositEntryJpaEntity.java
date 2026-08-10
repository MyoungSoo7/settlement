package github.lms.lemuel.deposit.adapter.out.persistence;

import github.lms.lemuel.deposit.domain.DepositEntryType;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * deposit_entries JPA 엔티티 (append-only).
 *
 * <p>UNIQUE(account_id, entry_type, reference_type, reference_id, offset_sequence) 제약이
 * 멱등 2차 방어선이다.
 */
@Entity
@Table(
    name = "deposit_entries",
    schema = "opslab"
)
@EntityListeners(AuditingEntityListener.class)
public class DepositEntryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 20)
    private DepositEntryType entryType;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "reference_id", length = 100)
    private String referenceId;

    @Column(name = "reference_type", length = 50)
    private String referenceType;

    @Column(name = "offset_sequence", nullable = false)
    private int offsetSequence;

    @Column(name = "source_hold_id")
    private Long sourceHoldId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected DepositEntryJpaEntity() {}

    public DepositEntryJpaEntity(Long accountId, DepositEntryType entryType, BigDecimal amount,
                                  String referenceId, String referenceType,
                                  int offsetSequence, Long sourceHoldId) {
        this.accountId = accountId;
        this.entryType = entryType;
        this.amount = amount;
        this.referenceId = referenceId;
        this.referenceType = referenceType;
        this.offsetSequence = offsetSequence;
        this.sourceHoldId = sourceHoldId;
    }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public DepositEntryType getEntryType() { return entryType; }
    public BigDecimal getAmount() { return amount; }
    public String getReferenceId() { return referenceId; }
    public String getReferenceType() { return referenceType; }
    public int getOffsetSequence() { return offsetSequence; }
    public Long getSourceHoldId() { return sourceHoldId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
