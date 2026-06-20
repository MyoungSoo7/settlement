package github.lms.lemuel.order.application.port.in;

import github.lms.lemuel.order.domain.Order;

import java.util.List;

public interface CreateMultiItemOrderUseCase {

    Order create(Long userId, List<Line> lines);

    /**
     * 주문 생성 요청에서 들어오는 1 라인.
     *
     * @param productId  상품 ID (필수)
     * @param variantId  옵션(SKU) 사용 시 지정. 없으면 null — 단일 상품
     * @param quantity   수량 (양수)
     */
    record Line(Long productId, Long variantId, int quantity) {
        public Line {
            if (productId == null) throw new IllegalArgumentException("productId 필수");
            if (quantity <= 0) throw new IllegalArgumentException("quantity 는 양수");
        }
    }
}
