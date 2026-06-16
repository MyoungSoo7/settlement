package github.lms.lemuel.loan.domain;

/**
 * loan 자체 원장의 계정과목.
 */
public enum LedgerAccount {
    LOAN_RECEIVABLE,    // 대출채권 (자산)
    CASH,               // 현금/funding
    FEE_RECEIVABLE,     // 미수수익 (자산)
    FEE_INCOME,         // 이자/수수료수익 (수익)
    BAD_DEBT_EXPENSE,   // 대손상각비 (비용)
    BAD_DEBT_ALLOWANCE  // 대손충당금 (자산 차감)
}
