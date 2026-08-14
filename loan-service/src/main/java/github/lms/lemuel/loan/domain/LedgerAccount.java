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
    BAD_DEBT_ALLOWANCE,        // 대손충당금 (자산 차감)

    // ─── Phase 2 (담보/개인신용 대출) ────────────────────────────────────────
    GUARANTEE_FEE_EXPENSE,     // 보증료비용 (비용) — 보증기관에 선취 지급
    COLLATERAL_DISPOSAL_LOSS,  // 담보처분손실 (비용) — 처분 회수액이 채권에 미달한 몫
    COLLATERAL_DISPOSAL_GAIN,  // 담보처분이익 (수익) — 처분 회수액이 채권을 초과한 몫

    // ─── 리스·할부 물건금융 ───────────────────────────────────────────────────
    /** 리스채권 (자산) — 계약 개시 시 인식하는 회수 대상. 대출채권과 성격은 같지만 물건이 담보다. */
    LEASE_RECEIVABLE
}
