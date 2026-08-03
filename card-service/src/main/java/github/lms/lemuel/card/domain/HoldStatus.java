package github.lms.lemuel.card.domain;

/**
 * 승인 홀드(Authorization Hold) 생명주기 상태.
 *
 * <p>상태 전이도:
 * <pre>
 * ACTIVE → CAPTURED          (전액 매입)
 * ACTIVE → PARTIALLY_CAPTURED → CAPTURED  (부분 매입 후 완료)
 * ACTIVE → VOIDED            (취소)
 * ACTIVE → EXPIRED           (미매입 만료 배치)
 * PARTIALLY_CAPTURED → VOIDED (부분 매입 후 나머지 취소)
 * </pre>
 */
public enum HoldStatus {
    /** 승인 완료, 매입 대기 중 — 가용한도 차감 중 */
    ACTIVE,
    /** 전액 매입 완료 — 홀드 소진 */
    CAPTURED,
    /** 부분 매입 완료, 잔여 홀드 활성 — 가용한도에서 잔여분 계속 차감 */
    PARTIALLY_CAPTURED,
    /** 취소(void) — 홀드 전액 원복, 가용한도 복구 */
    VOIDED,
    /** 미매입 만료 배치가 소멸시킴 — 가용한도 복구 */
    EXPIRED
}
