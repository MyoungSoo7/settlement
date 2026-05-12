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
    /** 수수료 인식. */
    COMMISSION_RECOGNIZED,
    /** 실 이체(출금) 실행. */
    PAYOUT_EXECUTED
}
