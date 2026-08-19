package github.lms.lemuel.report.domain;

/**
 * 매출을 가르는 축.
 *
 * <p>축을 enum 으로 고정하는 이유는 SQL 때문이다. 집계 컬럼을 문자열로 받으면 그 자리가 곧
 * 주입 지점이 된다 — 여기 열거된 다섯 개 밖의 값은 애초에 어댑터에 도달하지 못한다.
 *
 * <p>앞의 셋은 <b>구성비</b>(값의 가짓수가 적어 전부 보여도 된다), 뒤의 둘은 <b>랭킹</b>
 * (값이 계정 수만큼 늘어나 상위 N 으로 잘라야 한다)이다. 계산은 같으므로 타입은 나누지 않고
 * 상위 N 클램프만 서비스가 공통으로 건다.
 */
public enum SalesDimension {

    /** 결제수단 — settlement_payment_view.payment_method */
    PAYMENT_METHOD,
    /** 셀러 등급 — settlement_payment_view.seller_tier (정산 시점 등급) */
    SELLER_TIER,
    /** 정산 상태 — settlements.status */
    SETTLEMENT_STATUS,
    /** 셀러 랭킹 — settlement_payment_view.seller_id */
    SELLER,
    /** 상품 랭킹 — settlement_order_view.product_id */
    PRODUCT
}
