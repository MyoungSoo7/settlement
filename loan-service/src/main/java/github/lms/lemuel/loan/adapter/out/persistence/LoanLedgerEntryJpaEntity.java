package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.domain.LedgerAccount;
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

@Entity
@Table(name = "loan_ledger_entries")
public class LoanLedgerEntryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "debit", nullable = false, length = 30)
    private LedgerAccount debit;

    @Enumerated(EnumType.STRING)
    @Column(name = "credit", nullable = false, length = 30)
    private LedgerAccount credit;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "ref_type", nullable = false, length = 20)
    private String refType;

    @Column(name = "ref_id", nullable = false)
    private Long refId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected LoanLedgerEntryJpaEntity() { }

    public LoanLedgerEntryJpaEntity(LedgerAccount debit, LedgerAccount credit, BigDecimal amount,
                                    String refType, Long refId) {
        this.debit = debit;
        this.credit = credit;
        this.amount = amount;
        this.refType = refType;
        this.refId = refId;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public LedgerAccount getDebit() { return debit; }
    public LedgerAccount getCredit() { return credit; }
    public BigDecimal getAmount() { return amount; }
    public String getRefType() { return refType; }
    public Long getRefId() { return refId; }
}
