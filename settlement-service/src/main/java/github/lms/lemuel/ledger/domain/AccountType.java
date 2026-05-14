package github.lms.lemuel.ledger.domain;

/**
 * 원장 계정과목.
 *
 * <p>차변/대변 어느 쪽에 오는지는 거래 유형(LedgerEntryType)에 의해 결정되며,
 * 본 enum 자체는 부호를 갖지 않는다.
 */
public enum AccountType {
    /** 미수금 — 셀러가 받을 돈. */
    ACCOUNTS_RECEIVABLE,
    /** 미지급금 — 플랫폼이 셀러에게 지급할 돈. */
    ACCOUNTS_PAYABLE,
    /** 매출 — 상품 판매 수익. */
    REVENUE,
    /** 수수료 수익 — 플랫폼 수익. */
    COMMISSION_REVENUE,
    /** 수수료 비용 — 셀러가 부담하는 비용. */
    COMMISSION_EXPENSE,
    /** 매출 환불. */
    SALES_REFUND,
    /** 현금 — 실제 이체 시. */
    CASH
}
