package github.lms.lemuel.ledger.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "journal_entries")
@Getter
@NoArgsConstructor
public class JournalEntryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entry_type", nullable = false, length = 50)
    private String entryType;

    @Column(name = "reference_type", nullable = false, length = 30)
    private String referenceType;

    @Column(name = "reference_id", nullable = false)
    private Long referenceId;

    @Column
    private String description;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @OneToMany(mappedBy = "journalEntry", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LedgerLineJpaEntity> lines = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public JournalEntryJpaEntity(String entryType, String referenceType, Long referenceId,
                                  String description, String idempotencyKey) {
        this.entryType = entryType;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.description = description;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = LocalDateTime.now();
    }

    public void addLine(LedgerLineJpaEntity line) {
        lines.add(line);
        line.setJournalEntry(this);
    }
}
