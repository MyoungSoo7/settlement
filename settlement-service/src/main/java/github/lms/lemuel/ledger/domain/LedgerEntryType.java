package github.lms.lemuel.ledger.domain;

/**
 * 원장 분개 발생 유형.
 *
 * <p>한 거래가 여러 row 의 LedgerEntry 로 분해될 때, 같은 LedgerEntryType 을 공유한다.
 */
public enum LedgerEntryType {
    /** 정산 생성 시 최초 분개 (REQUESTED). */
    SETTLEMENT_CREATED,
    /** 정산 확정 시 전기 (DONE). */
    SETTLEMENT_CONFIRMED,
    /** 환불에 의한 역분개. */
    REFUND_REVERSED,
    /** 카드사 분쟁(Chargeback) ACCEPTED 에 의한 역분개. */
    CHARGEBACK_REVERSED,
    /** PG 대사 clawback 에 의한 역분개. */
    RECON_REVERSED,
    /** 수수료 인식. */
    COMMISSION_RECOGNIZED,
    /** 실 이체(출금) 실행. */
    PAYOUT_EXECUTED,
    /** 지급후 회수 채권 인식 — Dr 미수금(AR) / Cr 미지급금(AP) (seed-p0-6). */
    RECOVERY_RECOGNIZED,
    /** 채권 상계 — 후속 정산 지급액 차감. Dr 미지급금(AP) / Cr 미수금(AR). */
    RECOVERY_OFFSET,
    /**
     * 부가세 예수 — Dr 수수료수익(COMMISSION_REVENUE) / Cr 부가세예수금(VAT_PAYABLE) (ADR 0027 포함과세,
     * 2026-07-24 정정). 원천징수는 이 원장에 전기하지 않는다(account-service GL 로 이관, ADR 0026 확장).
     */
    VAT_ACCRUED
}
