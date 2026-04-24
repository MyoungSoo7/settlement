package github.lms.lemuel.ledger.domain.exception;

import github.lms.lemuel.ledger.domain.Money;

public class UnbalancedJournalEntryException extends RuntimeException {
    private final Money totalDebit;
    private final Money totalCredit;

    public UnbalancedJournalEntryException(Money totalDebit, Money totalCredit) {
        super(String.format("Journal entry is unbalanced: debit=%s, credit=%s",
                totalDebit.amount(), totalCredit.amount()));
        this.totalDebit = totalDebit;
        this.totalCredit = totalCredit;
    }

    public Money getTotalDebit() { return totalDebit; }
    public Money getTotalCredit() { return totalCredit; }
}
