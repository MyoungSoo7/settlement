package github.lms.lemuel.ledger.domain;

import java.time.LocalDateTime;

public class LedgerLine {

    private Long id;
    private Account account;
    private DebitCredit side;
    private Money amount;
    private LocalDateTime createdAt;

    private LedgerLine() {}

    public static LedgerLine debit(Account account, Money amount) {
        return create(account, DebitCredit.DEBIT, amount);
    }

    public static LedgerLine credit(Account account, Money amount) {
        return create(account, DebitCredit.CREDIT, amount);
    }

    private static LedgerLine create(Account account, DebitCredit side, Money amount) {
        if (account == null) {
            throw new IllegalArgumentException("Account cannot be null");
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("LedgerLine amount must be positive");
        }
        LedgerLine line = new LedgerLine();
        line.account = account;
        line.side = side;
        line.amount = amount;
        line.createdAt = LocalDateTime.now();
        return line;
    }

    // Reconstitution constructor
    public LedgerLine(Long id, Account account, DebitCredit side, Money amount, LocalDateTime createdAt) {
        this.id = id;
        this.account = account;
        this.side = side;
        this.amount = amount;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Account getAccount() { return account; }
    public DebitCredit getSide() { return side; }
    public Money getAmount() { return amount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
