package github.lms.lemuel.payment.domain;

public enum PaymentStatus {
    READY,      // 결제 생성(요청 준비)
    AUTHORIZED, // PG승인됨(카드/간편결제 승인)
    CAPTURED,   // 매입/확정(실 결제 완료->정산 대상)
    FAILED,     // 실패
    CANCELED,   // 승인 취소
    REFUNDED    // 환불
}
