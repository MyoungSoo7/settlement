package github.lms.lemuel.ledger.domain;

/**
 * 원장 계정과목.
 *
 * <p>차변/대변 어느 쪽에 오는지는 거래 유형(LedgerEntryType)에 의해 결정되며,
 * 본 enum 자체는 부호를 갖지 않는다.
 */
public enum AccountType {
    /** 미수금(자산) — 플랫폼이 받을 돈. 지급후 회수 채권(seed-p0-6: 셀러에게서 회수할 금액) 등. */
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
    CASH,
    /**
     * 부가세 예수금(부채, 대변성) — 플랫폼이 수수료(포함과세)에서 갈라낸 VAT 부분, 국세청 납부 전까지 예수
     * (ADR 0029, 2026-07-24 정정: 외부과세 AR 모델 폐기 → 포함과세로 COMMISSION_REVENUE 에서 분리).
     *
     * <p>원천징수 예수금은 이 원장에 없다 — 원천징수는 실제 payout 지급액에서 공제되고, 그 결과 남는
     * SELLER_PAYABLE 잔여를 닫는 WITHHOLDING_PAYABLE 계정은 <b>account-service GL</b>에 있다
     * (ADR 0026 폐루프의 확장, settlement 는 이벤트만 발행).
     */
    VAT_PAYABLE
}
