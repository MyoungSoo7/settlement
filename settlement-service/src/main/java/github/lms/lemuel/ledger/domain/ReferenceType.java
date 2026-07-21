package github.lms.lemuel.ledger.domain;

/**
 * LedgerEntry 가 참조하는 원거래 종류.
 *
 * <p>{@code (reference_id, reference_type)} 쌍이 한 비즈니스 거래를 식별한다.
 */
public enum ReferenceType {
    SETTLEMENT,
    REFUND,
    /** 카드사 분쟁(Chargeback) ACCEPTED 에 따른 역분개의 원거래. reference_id = chargeback_id. */
    CHARGEBACK,
    /** PG 대사 차이(Discrepancy) 승인 clawback 에 따른 역분개의 원거래. reference_id = discrepancy_id. */
    PG_RECONCILIATION
}
