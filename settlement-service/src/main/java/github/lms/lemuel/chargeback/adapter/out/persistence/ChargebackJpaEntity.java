package github.lms.lemuel.chargeback.adapter.out.persistence;

import github.lms.lemuel.chargeback.domain.ChargebackReason;
import github.lms.lemuel.chargeback.domain.ChargebackSource;
import github.lms.lemuel.chargeback.domain.ChargebackStatus;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "chargebacks")
public class ChargebackJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "settlement_id")
    private Long settlementId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 50)
    private ChargebackReason reasonCode;

    @Column(name = "reason_detail", columnDefinition = "text")
    private String reasonDetail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChargebackStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChargebackSource source;

    @Column(name = "pg_chargeback_id", length = 128)
    private String pgChargebackId;

    @Column(name = "decided_by", length = 255)
    private String decidedBy;

    @Column(name = "decision_note", columnDefinition = "text")
    private String decisionNote;

    @Column(name = "raised_at", nullable = false)
    private LocalDateTime raisedAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ChargebackJpaEntity() { }

    public ChargebackJpaEntity(Long id, Long paymentId, Long settlementId, BigDecimal amount,
                                ChargebackReason reasonCode, String reasonDetail,
                                ChargebackStatus status, ChargebackSource source, String pgChargebackId,
                                String decidedBy, String decisionNote,
                                LocalDateTime raisedAt, LocalDateTime decidedAt,
                                LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.settlementId = settlementId;
        this.amount = amount;
        this.reasonCode = reasonCode;
        this.reasonDetail = reasonDetail;
        this.status = status;
        this.source = source;
        this.pgChargebackId = pgChargebackId;
        this.decidedBy = decidedBy;
        this.decisionNote = decisionNote;
        this.raisedAt = raisedAt;
        this.decidedAt = decidedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** 도메인이 결정한 새 상태를 반영. */
    public void applyDecision(ChargebackStatus status, Long settlementId,
                               String decidedBy, String decisionNote,
                               LocalDateTime decidedAt, LocalDateTime updatedAt) {
        this.status = status;
        if (settlementId != null) this.settlementId = settlementId;  // null 일 때 기존 값 보존
        this.decidedBy = decidedBy;
        this.decisionNote = decisionNote;
        this.decidedAt = decidedAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() { return id; }
    public Long getPaymentId() { return paymentId; }
    public Long getSettlementId() { return settlementId; }
    public BigDecimal getAmount() { return amount; }
    public ChargebackReason getReasonCode() { return reasonCode; }
    public String getReasonDetail() { return reasonDetail; }
    public ChargebackStatus getStatus() { return status; }
    public ChargebackSource getSource() { return source; }
    public String getPgChargebackId() { return pgChargebackId; }
    public String getDecidedBy() { return decidedBy; }
    public String getDecisionNote() { return decisionNote; }
    public LocalDateTime getRaisedAt() { return raisedAt; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
