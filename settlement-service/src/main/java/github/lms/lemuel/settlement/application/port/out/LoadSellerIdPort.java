package github.lms.lemuel.settlement.application.port.out;

import java.util.Optional;

/**
 * 결제(payment) 로부터 판매자 ID 를 해석하는 포트.
 *
 * <p>경로: {@code payment.order_id → orders.product_id → products.seller_id}.
 * loan-service 로 보내는 정산 이벤트(SettlementCreated/Confirmed)의 {@code sellerId} 를 채우는 데 쓰인다.
 * Settlement 도메인은 sellerId 를 보관하지 않으므로(paymentId/orderId 만) 발행 시점에 해석한다.
 */
public interface LoadSellerIdPort {

    /** @return 판매자 ID. 미할당(seller_id NULL)·매핑 실패 시 {@code Optional.empty()}. */
    Optional<Long> findSellerIdByPaymentId(Long paymentId);
}
