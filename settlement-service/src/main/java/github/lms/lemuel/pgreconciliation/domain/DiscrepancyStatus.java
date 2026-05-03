package github.lms.lemuel.pgreconciliation.domain;

public enum DiscrepancyStatus {
    /** 운영자 검토 대기 */
    PENDING,
    /** 운영자 승인 — 후속 역정산/보정 처리 대상 */
    APPROVED,
    /** 운영자가 무시 결정 (사후 추적용 로그만 남음) */
    REJECTED,
    /** ROUNDING_DIFF 등 시스템이 즉시 자동 보정 */
    AUTO_CORRECTED
}
