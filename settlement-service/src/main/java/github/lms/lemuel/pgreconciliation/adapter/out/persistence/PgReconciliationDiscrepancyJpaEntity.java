package github.lms.lemuel.pgreconciliation.adapter.out.persistence;

import github.lms.lemuel.pgreconciliation.domain.DiscrepancyStatus;
import github.lms.lemuel.pgreconciliation.domain.DiscrepancyType;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "pg_reconciliation_discrepancies")
public class PgReconciliationDiscrepancyJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DiscrepancyType type;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "pg_transaction_id", length = 500)
    private String pgTransactionId;

    @Column(name = "internal_amount", precision = 12, scale = 2)
    private BigDecimal internalAmount;

    @Column(name = "pg_amount", precision = 12, scale = 2)
    private BigDecimal pgAmount;

    @Column(name = "difference", precision = 12, scale = 2)
    private BigDecimal difference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DiscrepancyStatus status;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    @Column(columnDefinition = "text")
    private String note;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected PgReconciliationDiscrepancyJpaEntity() { }

    public PgReconciliationDiscrepancyJpaEntity(Long id, Long runId, DiscrepancyType type,
                                                Long paymentId, String pgTransactionId,
                                                BigDecimal internalAmount, BigDecimal pgAmount,
                                                BigDecimal difference, DiscrepancyStatus status,
                                                LocalDateTime resolvedAt, String resolvedBy, String note,
                                                LocalDateTime createdAt) {
        this.id = id;
        this.runId = runId;
        this.type = type;
        this.paymentId = paymentId;
        this.pgTransactionId = pgTransactionId;
        this.internalAmount = internalAmount;
        this.pgAmount = pgAmount;
        this.difference = difference;
        this.status = status;
        this.resolvedAt = resolvedAt;
        this.resolvedBy = resolvedBy;
        this.note = note;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getRunId() { return runId; }
    public DiscrepancyType getType() { return type; }
    public Long getPaymentId() { return paymentId; }
    public String getPgTransactionId() { return pgTransactionId; }
    public BigDecimal getInternalAmount() { return internalAmount; }
    public BigDecimal getPgAmount() { return pgAmount; }
    public BigDecimal getDifference() { return difference; }
    public DiscrepancyStatus getStatus() { return status; }
    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public String getResolvedBy() { return resolvedBy; }
    public String getNote() { return note; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
