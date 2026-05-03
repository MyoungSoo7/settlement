package github.lms.lemuel.payout.domain;

/**
 * 출금 상태머신.
 *
 * <pre>
 *   REQUESTED → SENDING → COMPLETED
 *                       → FAILED → (운영자 retry) → REQUESTED
 *                       → CANCELED (운영자 취소 — 종결)
 * </pre>
 */
public enum PayoutStatus {
    /** 송금 요청됨 — 펌뱅킹 호출 대기 */
    REQUESTED,
    /** 펌뱅킹 호출 진행 중 — 응답 대기 */
    SENDING,
    /** 송금 완료 — 셀러 통장 입금 확인 */
    COMPLETED,
    /** 송금 실패 — 운영자 검토 후 retry / cancel */
    FAILED,
    /** 운영자가 영구 취소 — 종결 상태 */
    CANCELED
}
