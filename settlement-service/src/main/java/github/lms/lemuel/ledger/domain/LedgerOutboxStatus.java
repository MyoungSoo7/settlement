package github.lms.lemuel.ledger.domain;

/**
 * 원장 아웃박스 row 상태.
 *
 * <p>{@code PENDING} → {@code DONE}(처리 완료) 또는 {@code FAILED}(재시도 한도 초과).
 */
public enum LedgerOutboxStatus {
    PENDING,
    DONE,
    FAILED
}
