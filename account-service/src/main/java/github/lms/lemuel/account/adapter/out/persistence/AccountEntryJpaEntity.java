package github.lms.lemuel.account.adapter.out.persistence;

import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.account.domain.OwnerType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * GL 분개 영속 엔티티 (account_entries). 자연키 (source_topic, ref_type, ref_id) 는 V1 UNIQUE 로 강제.
 */
@Entity
@Table(name = "account_entries")
public class AccountEntryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 20)
    private OwnerType ownerType;

    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "debit_account", nullable = false, length = 40)
    private GlAccount debitAccount;

    @Enumerated(EnumType.STRING)
    @Column(name = "credit_account", nullable = false, length = 40)
    private GlAccount creditAccount;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "ref_type", nullable = false, length = 40)
    private String refType;

    @Column(name = "ref_id", nullable = false, length = 64)
    private String refId;

    @Column(name = "source_topic", nullable = false, length = 100)
    private String sourceTopic;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    protected AccountEntryJpaEntity() { }

    public AccountEntryJpaEntity(OwnerType ownerType, String ownerId, GlAccount debitAccount,
                                 GlAccount creditAccount, BigDecimal amount, String refType,
                                 String refId, String sourceTopic, LocalDateTime occurredAt) {
        this.ownerType = ownerType;
        this.ownerId = ownerId;
        this.debitAccount = debitAccount;
        this.creditAccount = creditAccount;
        this.amount = amount;
        this.refType = refType;
        this.refId = refId;
        this.sourceTopic = sourceTopic;
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public OwnerType getOwnerType() { return ownerType; }
    public String getOwnerId() { return ownerId; }
    public GlAccount getDebitAccount() { return debitAccount; }
    public GlAccount getCreditAccount() { return creditAccount; }
    public BigDecimal getAmount() { return amount; }
    public String getRefType() { return refType; }
    public String getRefId() { return refId; }
    public String getSourceTopic() { return sourceTopic; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
}
