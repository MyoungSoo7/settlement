package github.lms.lemuel.ledger.domain;

import github.lms.lemuel.ledger.domain.exception.UnbalancedJournalEntryException;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

public class JournalEntry {

    private Long id;
    private String entryType;
    private String referenceType;
    private Long referenceId;
    private List<LedgerLine> lines;
    private String description;
    private String idempotencyKey;
    private LocalDateTime createdAt;

    private JournalEntry() {}

    public static JournalEntry create(String entryType, String referenceType,
                                       Long referenceId, List<LedgerLine> lines,
                                       String idempotencyKey, String description) {
        if (lines == null || lines.size() < 2) {
            throw new IllegalArgumentException("Journal entry must have at least 2 lines");
        }

        Money totalDebit = lines.stream()
                .filter(l -> l.getSide() == DebitCredit.DEBIT)
                .map(LedgerLine::getAmount)
                .reduce(Money.ZERO, Money::add);

        Money totalCredit = lines.stream()
                .filter(l -> l.getSide() == DebitCredit.CREDIT)
                .map(LedgerLine::getAmount)
                .reduce(Money.ZERO, Money::add);

        if (!totalDebit.equals(totalCredit)) {
            throw new UnbalancedJournalEntryException(totalDebit, totalCredit);
        }

        JournalEntry entry = new JournalEntry();
        entry.entryType = entryType;
        entry.referenceType = referenceType;
        entry.referenceId = referenceId;
        entry.lines = List.copyOf(lines);
        entry.idempotencyKey = idempotencyKey;
        entry.description = description;
        entry.createdAt = LocalDateTime.now();
        return entry;
    }

    public Money totalDebit() {
        return lines.stream()
                .filter(l -> l.getSide() == DebitCredit.DEBIT)
                .map(LedgerLine::getAmount)
                .reduce(Money.ZERO, Money::add);
    }

    public Money totalCredit() {
        return lines.stream()
                .filter(l -> l.getSide() == DebitCredit.CREDIT)
                .map(LedgerLine::getAmount)
                .reduce(Money.ZERO, Money::add);
    }

    // Reconstitution constructor
    public JournalEntry(Long id, String entryType, String referenceType, Long referenceId,
                         List<LedgerLine> lines, String description, String idempotencyKey,
                         LocalDateTime createdAt) {
        this.id = id;
        this.entryType = entryType;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.lines = lines;
        this.description = description;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getEntryType() { return entryType; }
    public String getReferenceType() { return referenceType; }
    public Long getReferenceId() { return referenceId; }
    public List<LedgerLine> getLines() { return Collections.unmodifiableList(lines); }
    public String getDescription() { return description; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
