package github.lms.lemuel.chargeback.domain;

/**
 * 카드사 분쟁 사유 코드.
 *
 * <p>실제 카드사 reason code 는 Visa/Master 가 수십 개로 세분화되어 있지만,
 * 운영 보고용으로는 5 개 카테고리로 묶는 것이 통상적이다.
 */
public enum ChargebackReason {
    /** 도용·미인지 결제. 가장 흔한 분쟁 유형. */
    FRAUD,
    /** 동일 결제 중복 청구. */
    DUPLICATE,
    /** 상품 미수령. */
    NOT_RECEIVED,
    /** 상품 설명과 다름·하자. */
    PRODUCT_NOT_AS_DESCRIBED,
    /** 위 카테고리에 해당하지 않는 기타. */
    OTHER
}
