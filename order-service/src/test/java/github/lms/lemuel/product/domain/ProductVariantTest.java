package github.lms.lemuel.product.domain;

import github.lms.lemuel.product.domain.exception.InsufficientStockException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductVariantTest {

    @Test
    @DisplayName("생성: 초기 재고 0 이면 OUT_OF_STOCK 으로 시작")
    void create_zeroStock_isOutOfStock() {
        var v = ProductVariant.create(1L, "SKU-RED-L", "색상:빨강/사이즈:L",
                new BigDecimal("1000"), 0);

        assertThat(v.getStatus()).isEqualTo(ProductVariantStatus.OUT_OF_STOCK);
        assertThat(v.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("생성: 초기 재고 양수 → ACTIVE")
    void create_positiveStock_isActive() {
        var v = ProductVariant.create(1L, "SKU-RED-L", "색상:빨강/사이즈:L",
                BigDecimal.ZERO, 100);

        assertThat(v.getStatus()).isEqualTo(ProductVariantStatus.ACTIVE);
        assertThat(v.isAvailable()).isTrue();
        assertThat(v.getStockQuantity()).isEqualTo(100);
    }

    @Test
    @DisplayName("재고 차감 성공: 수량 감소 + 0 도달 시 OUT_OF_STOCK")
    void decrease_to_zero_marksOutOfStock() {
        var v = ProductVariant.create(1L, "SKU", "옵션", BigDecimal.ZERO, 5);

        v.decreaseStock(5);

        assertThat(v.getStockQuantity()).isZero();
        assertThat(v.getStatus()).isEqualTo(ProductVariantStatus.OUT_OF_STOCK);
    }

    @Test
    @DisplayName("재고 차감: 가용보다 많이 요청 시 InsufficientStockException")
    void decrease_exceedsStock() {
        var v = ProductVariant.create(1L, "SKU", "옵션", BigDecimal.ZERO, 3);

        assertThatThrownBy(() -> v.decreaseStock(5))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("재고 부족");
    }

    @Test
    @DisplayName("재고 차감: 0 또는 음수 수량은 IllegalArgumentException")
    void decrease_invalidQuantity() {
        var v = ProductVariant.create(1L, "SKU", "옵션", BigDecimal.ZERO, 5);

        assertThatThrownBy(() -> v.decreaseStock(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> v.decreaseStock(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("재고 차감: 단종된 SKU 는 IllegalStateException")
    void decrease_discontinuedSku() {
        var v = ProductVariant.create(1L, "SKU", "옵션", BigDecimal.ZERO, 5);
        v.discontinue();

        assertThatThrownBy(() -> v.decreaseStock(1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("단종");
    }

    @Test
    @DisplayName("재고 증가: OUT_OF_STOCK 상태에서 재고 들어오면 ACTIVE 로 자동 복귀")
    void increase_recoversFromOutOfStock() {
        var v = ProductVariant.create(1L, "SKU", "옵션", BigDecimal.ZERO, 0);
        assertThat(v.getStatus()).isEqualTo(ProductVariantStatus.OUT_OF_STOCK);

        v.increaseStock(10);

        assertThat(v.getStatus()).isEqualTo(ProductVariantStatus.ACTIVE);
        assertThat(v.getStockQuantity()).isEqualTo(10);
    }

    @Test
    @DisplayName("rehydrate: 영속 상태에서 복원 — version 보존")
    void rehydrate_preservesVersion() {
        var v = ProductVariant.rehydrate(99L, 1L, "SKU", "옵션",
                new BigDecimal("500"), 50, 7L,
                ProductVariantStatus.ACTIVE, null, null);

        assertThat(v.getId()).isEqualTo(99L);
        assertThat(v.getVersion()).isEqualTo(7L);
    }

    @Test
    @DisplayName("생성 검증: optionName / sku 비어있으면 IllegalArgumentException")
    void create_validation() {
        assertThatThrownBy(() -> ProductVariant.create(1L, "", "옵션", BigDecimal.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductVariant.create(1L, "SKU", "", BigDecimal.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductVariant.create(1L, "SKU", "옵션", BigDecimal.ZERO, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
