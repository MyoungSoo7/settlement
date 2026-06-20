package github.lms.lemuel.settlement.application.port.out;

import github.lms.lemuel.settlement.domain.SellerTier;

import java.util.Optional;

/**
 * 결제(payment) 로부터 판매자 등급을 해석하는 포트.
 *
 * <p>경로: {@code payment.order_id → orders.product_id → products.seller_id → users.seller_tier}.
 * 미할당 상품(seller_id IS NULL) 이거나 조회 실패 시 NORMAL 로 fallback 하도록 어댑터가 처리한다.
 */
public interface LoadSellerTierPort {

    /**
     * @return 해당 결제의 판매자 등급. 매핑이 없으면 {@code Optional.empty()} —
     *   호출부는 {@link SellerTier#NORMAL} 로 fallback.
     */
    Optional<SellerTier> findTierByPaymentId(Long paymentId);
}
