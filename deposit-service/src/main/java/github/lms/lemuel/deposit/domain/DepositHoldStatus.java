package github.lms.lemuel.deposit.domain;

/**
 * 예치금 hold 상태머신.
 *
 * <pre>
 * ACTIVE ──► PARTIALLY_CAPTURED ──► CAPTURED
 *    │               │
 *    ▼               ▼
 *  EXPIRED         RELEASED
 *    │
 *    ▼
 *  VOIDED
 * </pre>
 */
public enum DepositHoldStatus {
    /** 활성 — 재원이 locked 에 있음. */
    ACTIVE,
    /** 부분 캡처됨 — 일부는 상계, 나머지는 locked 에 남아 있음. */
    PARTIALLY_CAPTURED,
    /** 전액 캡처됨 — locked 에서 제거 완료. */
    CAPTURED,
    /** 만료 — TTL 초과로 available 에 반환됨. */
    EXPIRED,
    /** 취소 — 명시적 취소로 available 에 반환됨. */
    VOIDED,
    /** 해제 — hold 잔여분이 release 로 available 에 반환됨. */
    RELEASED
}
