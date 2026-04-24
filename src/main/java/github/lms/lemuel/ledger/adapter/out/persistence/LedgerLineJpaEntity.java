package github.lms.lemuel.ledger.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "ledger_lines")
@Getter
@NoArgsConstructor
public class LedgerLineJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "journal_entry_id", nullable = false)
    @Setter
    private JournalEntryJpaEntity journalEntry;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(nullable = false, length = 6)
    private String side;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public LedgerLineJpaEntity(Long accountId, String side, BigDecimal amount) {
        this.accountId = accountId;
        this.side = side;
        this.amount = amount;
        this.createdAt = LocalDateTime.now();
    }
}
