package github.lms.lemuel.loan.application.port.out;

import github.lms.lemuel.loan.domain.LoanLedgerEntry;

/**
 * loan 자체 원장 전표 기록 아웃바운드 포트.
 */
public interface AppendLedgerPort {
    void append(LoanLedgerEntry entry);
}
