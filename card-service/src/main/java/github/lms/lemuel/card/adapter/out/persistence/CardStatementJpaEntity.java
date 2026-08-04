package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.domain.CardStatement;
import github.lms.lemuel.card.domain.StatementStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * card_statements 테이블 매핑 (V8).
 */
@Entity
@Table(name = "card_statements")
public class CardStatementJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "card_account_id", nullable = false)
    private Long cardAccountId;

    @Column(name = "billing_year_month", nullable = false, length = 7)
    private String billingYearMonth;   // 'YYYY-MM' 형식

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatementStatus status;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal paidAmount;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected CardStatementJpaEntity() {
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public static CardStatementJpaEntity fromDomain(CardStatement s) {
        CardStatementJpaEntity e = new CardStatementJpaEntity();
        e.id = s.getId();
        e.cardAccountId = s.getCardAccountId();
        e.billingYearMonth = s.getBillingYearMonth().toString();  // 'YYYY-MM'
        e.status = s.getStatus();
        e.totalAmount = s.getTotalAmount();
        e.paidAmount = s.getPaidAmount();
        e.dueDate = s.getDueDate();
        e.closedAt = s.getClosedAt();
        e.version = s.getVersion();
        return e;
    }

    public CardStatement toDomain() {
        return CardStatement.builder()
                .id(id)
                .cardAccountId(cardAccountId)
                .billingYearMonth(YearMonth.parse(billingYearMonth))
                .status(status)
                .totalAmount(totalAmount)
                .paidAmount(paidAmount)
                .dueDate(dueDate)
                .closedAt(closedAt)
                .version(version)
                .build();
    }

    public Long getId() {
        return id;
    }
}
