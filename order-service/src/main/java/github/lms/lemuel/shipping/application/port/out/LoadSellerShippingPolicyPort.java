package github.lms.lemuel.shipping.application.port.out;

import github.lms.lemuel.shipping.domain.SellerShippingPolicy;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** 셀러 배송비 정책 조회 포트. 정책이 없는 셀러는 결과에서 빠지고, 기본배송비는 부과되지 않는다. */
public interface LoadSellerShippingPolicyPort {

    Map<Long, SellerShippingPolicy> loadBySellerIds(Collection<Long> sellerIds);

    Optional<SellerShippingPolicy> loadBySellerId(Long sellerId);
}
