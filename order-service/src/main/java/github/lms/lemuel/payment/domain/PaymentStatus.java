package github.lms.lemuel.payment.domain;

public enum PaymentStatus {
    READY,      // 결제 생성(요청 준비)
    AUTHORIZED, // PG승인됨(카드/간편결제 승인)
    CAPTURED,   // 매입/확정(실 결제 완료->정산 대상)
    FAILED,     // 실패
    CANCELED,   // 승인 취소
    REFUNDED;   // 환불

    /**
     * 허용 상태 전이 단일 출처(SettlementStatus#canTransitionTo 동형). 애그리거트(PaymentDomain)의
     * 전이 가드가 이 표에 위임한다 — 표에 없는 전이는 금지된다.
     *
     * <pre>
     * READY → AUTHORIZED → CAPTURED → REFUNDED
     *                    ↘ CANCELED (승인취소)
     * </pre>
     *
     * <p>FAILED 는 도메인 전이 메서드가 없어 어떤 전이의 원천/대상도 아니다(종료).
     */
    public boolean canTransitionTo(PaymentStatus target) {
        switch (this) {
            case READY:
                return target == AUTHORIZED;
            case AUTHORIZED:
                return target == CAPTURED || target == CANCELED;
            case CAPTURED:
                return target == REFUNDED;
            case FAILED:
            case CANCELED:
            case REFUNDED:
                return false; // 종료 상태
            default:
                return false;
        }
    }
}
