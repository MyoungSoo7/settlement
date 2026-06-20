package github.lms.lemuel.reservation.domain;

/**
 * 시공 예약 상태 Enum
 *
 * 상태머신: REQUESTED → CONFIRMED → ASSIGNED → IN_PROGRESS → COMPLETED ; → CANCELED
 */
public enum ReservationStatus {
    REQUESTED,    // 접수 (업체 회원이 등록)
    CONFIRMED,    // 관리자 확인
    ASSIGNED,     // 기사 배정 완료
    IN_PROGRESS,  // 시공 중
    COMPLETED,    // 시공 완료
    CANCELED;     // 취소

    public static ReservationStatus fromString(String status) {
        try {
            return ReservationStatus.valueOf(status.toUpperCase());
        } catch (Exception e) {
            return REQUESTED; // 기본값
        }
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELED;
    }
}
