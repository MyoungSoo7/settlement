package github.lms.lemuel.shipping.application.port.in;

import github.lms.lemuel.shipping.domain.SellerShippingPolicy;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 셀러 배송비 정책 등록·변경 — 운영 콘솔 전용.
 *
 * <p>배송비는 고객에게 청구되는 금액이므로 이 유스케이스는 ADMIN 게이트 뒤에 있어야 한다
 * (SecurityConfig 의 {@code /admin/shipping-policies/**}).
 */
public interface ManageSellerShippingPolicyUseCase {

    /**
     * 정책 등록 또는 변경(셀러당 1 건, upsert).
     *
     * @param freeThreshold {@code null} 이면 무료배송 조건 없음(항상 부과), 0 이면 항상 무료
     */
    SellerShippingPolicy upsert(Long sellerId, BigDecimal baseFee, BigDecimal freeThreshold);

    Optional<SellerShippingPolicy> find(Long sellerId);
}
