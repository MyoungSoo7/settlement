package github.lms.lemuel.product.application.port.in;

import github.lms.lemuel.product.domain.ProductVariant;

public interface DecreaseVariantStockUseCase {

    /**
     * 옵션(SKU) 재고를 quantity 만큼 차감.
     *
     * <p>동시성 정책: Optimistic Lock 충돌 시 내부적으로 N 회 재시도 후 최종 실패하면
     * {@link github.lms.lemuel.product.domain.exception.StockConcurrencyException} 발생.
     *
     * @throws github.lms.lemuel.product.domain.exception.InsufficientStockException 재고 부족
     * @throws github.lms.lemuel.product.domain.exception.StockConcurrencyException 재시도 한계 초과
     */
    ProductVariant decrease(Long variantId, int quantity);
}
