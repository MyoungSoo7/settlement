package github.lms.lemuel.ledger.domain;

/**
 * LedgerEntry 가 참조하는 원거래 종류.
 *
 * <p>{@code (reference_id, reference_type)} 쌍이 한 비즈니스 거래를 식별한다.
 */
public enum ReferenceType {
    SETTLEMENT,
    REFUND
}
