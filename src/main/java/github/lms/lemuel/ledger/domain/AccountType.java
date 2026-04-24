package github.lms.lemuel.ledger.domain;

public enum AccountType {
    ASSET(DebitCredit.DEBIT),
    LIABILITY(DebitCredit.CREDIT),
    REVENUE(DebitCredit.CREDIT),
    EXPENSE(DebitCredit.DEBIT);

    private final DebitCredit normalSide;

    AccountType(DebitCredit normalSide) {
        this.normalSide = normalSide;
    }

    public DebitCredit normalSide() {
        return normalSide;
    }
}
