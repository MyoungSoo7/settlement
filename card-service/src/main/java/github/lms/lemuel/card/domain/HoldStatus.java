package github.lms.lemuel.card.domain;

/**
 * 승인 홀드(Authorization Hold) 생명주기 상태.
 *
 * <p>상태 전이도:
 * <pre>
 * ACTIVE → CAPTURED              (전액 매입)
 * ACTIVE → PARTIALLY_CAPTURED → CAPTURED  (부분 매입 후 완료)
 * ACTIVE → VOIDED                (취소 — 가용한도 원복)
 * ACTIVE → EXPIRED               (미매입 만료 배치 — 가용한도 원복)
 * PARTIALLY_CAPTURED → VOIDED    (부분 매입 후 나머지 취소 — 가용한도 원복)
 * CAPTURED → REFUNDED            (환불 — 가용한도 원복)
 * PARTIALLY_CAPTURED → REFUNDED  (부분매입 환불 — 가용한도 원복)
 * </pre>
 *
 * <p>가용한도 차감 대상: ACTIVE · PARTIALLY_CAPTURED · CAPTURED.
 * 가용한도 복구 상태: VOIDED · EXPIRED · REFUNDED.
 */
public enum HoldStatus {
    /** 승인 완료, 매입 대기 중 — 가용한도 차감 중 */
    ACTIVE,
    /** 전액 매입 완료 — 가용한도에서 계속 차감(미결제 잔액 포함) */
    CAPTURED,
    /** 부분 매입 완료, 잔여 홀드 활성 — 가용한도에서 원금 계속 차감 */
    PARTIALLY_CAPTURED,
    /** 취소(void) — 홀드 전액 원복, 가용한도 복구 */
    VOIDED,
    /** 미매입 만료 배치가 소멸시킴 — 가용한도 복구 */
    EXPIRED,
    /** 환불(refund) — 매입 후 한도 원복 */
    REFUNDED
}
