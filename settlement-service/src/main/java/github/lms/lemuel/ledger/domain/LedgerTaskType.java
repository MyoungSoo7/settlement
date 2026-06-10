package github.lms.lemuel.ledger.domain;

/**
 * 원장 아웃박스 작업 종류.
 *
 * <p>{@code CREATE_ENTRY} — 정산 확정(DONE) 분개 작성, {@code settlementId} 만 사용.
 * <p>{@code REVERSE_ENTRY} — 환불 역분개 작성, refund 관련 필드까지 사용.
 */
public enum LedgerTaskType {
    CREATE_ENTRY,
    REVERSE_ENTRY
}
