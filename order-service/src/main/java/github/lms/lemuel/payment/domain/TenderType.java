package github.lms.lemuel.payment.domain;

/**
 * 분할결제 지불 수단 유형.
 *
 * <p>{@link #usesExternalPg()} 로 외부 PG 호출 여부를 판별 — POINT / GIFT_CARD 는 내부 잔액
 * 차감이므로 PG 호출 없이 즉시 캡처되고, 그 외는 PG 어댑터 경유.
 */
public enum TenderType {
    CARD(true),
    KAKAO_PAY(true),
    NAVER_PAY(true),
    PAYCO(true),
    SAMSUNG_PAY(true),
    BANK_TRANSFER(true),
    VIRTUAL_ACCOUNT(true),
    /** 멤버십 포인트 — 외부 PG 호출 없이 내부 잔액 차감 */
    POINT(false),
    /** 상품권 — 내부 잔액 차감 */
    GIFT_CARD(false);

    private final boolean usesExternalPg;

    TenderType(boolean usesExternalPg) {
        this.usesExternalPg = usesExternalPg;
    }

    public boolean usesExternalPg() {
        return usesExternalPg;
    }

    /**
     * 환불 우선순위 — 낮을수록 먼저 환불 (역순 환불 정책에서는 가장 마지막에 처리된 tender).
     * 일반적으로 외부 PG 가 먼저 환불되고 (실제 카드 결제 취소), 내부 잔액(포인트/상품권) 이 마지막에 복원.
     */
    public boolean isExternalFirst() {
        return usesExternalPg;
    }
}
