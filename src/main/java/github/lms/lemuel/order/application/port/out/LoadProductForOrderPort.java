package github.lms.lemuel.order.application.port.out;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Order에서 Product 가격 조회하는 Outbound Port
 */
public interface LoadProductForOrderPort {

    /**
     * 상품 ID로 가격 조회
     */
    Optional<BigDecimal> findPriceById(Long productId);

    /**
     * 상품 존재 여부 확인
     */
    boolean existsById(Long productId);
}
