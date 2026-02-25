package github.lms.lemuel.product.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

/**
 * Product Domain TDD Tests
 * 순수 도메인 로직 테스트 (Spring Context 불필요)
 */
@DisplayName("Product 도메인 테스트")
class ProductTest {

    @Nested
    @DisplayName("상품 생성 테스트")
    class CreateProductTest {

        @Test
        @DisplayName("정상적인 상품을 생성할 수 있다")
        void createProduct_Success() {
            // given
            String name = "테스트 상품";
            String description = "상품 설명";
            BigDecimal price = new BigDecimal("10000");
            Integer stock = 100;

            // when
            Product product = Product.create(name, description, price, stock);

            // then
            assertThat(product).isNotNull();
            assertThat(product.getName()).isEqualTo(name);
            assertThat(product.getDescription()).isEqualTo(description);
            assertThat(product.getPrice()).isEqualByComparingTo(price);
            assertThat(product.getStockQuantity()).isEqualTo(stock);
            assertThat(product.getStatus()).isEqualTo(ProductStatus.ACTIVE);
            assertThat(product.getCreatedAt()).isNotNull();
            assertThat(product.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("상품명이 null이면 예외가 발생한다")
        void createProduct_NullName_ThrowsException() {
            // given
            String name = null;
            String description = "상품 설명";
            BigDecimal price = new BigDecimal("10000");
            Integer stock = 100;

            // when & then
            assertThatThrownBy(() -> Product.create(name, description, price, stock))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Product name cannot be empty");
        }

        @Test
        @DisplayName("상품명이 빈 문자열이면 예외가 발생한다")
        void createProduct_EmptyName_ThrowsException() {
            // given
            String name = "   ";
            String description = "상품 설명";
            BigDecimal price = new BigDecimal("10000");
            Integer stock = 100;

            // when & then
            assertThatThrownBy(() -> Product.create(name, description, price, stock))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Product name cannot be empty");
        }

        @Test
        @DisplayName("상품명이 200자를 초과하면 예외가 발생한다")
        void createProduct_NameTooLong_ThrowsException() {
            // given
            String name = "a".repeat(201);
            String description = "상품 설명";
            BigDecimal price = new BigDecimal("10000");
            Integer stock = 100;

            // when & then
            assertThatThrownBy(() -> Product.create(name, description, price, stock))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not exceed 200 characters");
        }

        @Test
        @DisplayName("가격이 음수면 예외가 발생한다")
        void createProduct_NegativePrice_ThrowsException() {
            // given
            String name = "테스트 상품";
            String description = "상품 설명";
            BigDecimal price = new BigDecimal("-1000");
            Integer stock = 100;

            // when & then
            assertThatThrownBy(() -> Product.create(name, description, price, stock))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("price must be zero or greater");
        }

        @Test
        @DisplayName("재고 수량이 음수면 예외가 발생한다")
        void createProduct_NegativeStock_ThrowsException() {
            // given
            String name = "테스트 상품";
            String description = "상품 설명";
            BigDecimal price = new BigDecimal("10000");
            Integer stock = -10;

            // when & then
            assertThatThrownBy(() -> Product.create(name, description, price, stock))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Stock quantity must be zero or greater");
        }

        @Test
        @DisplayName("가격이 0원인 상품을 생성할 수 있다 (무료 상품)")
        void createProduct_ZeroPrice_Success() {
            // given
            String name = "무료 상품";
            BigDecimal price = BigDecimal.ZERO;
            Integer stock = 100;

            // when
            Product product = Product.create(name, null, price, stock);

            // then
            assertThat(product.getPrice()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("재고 관리 테스트")
    class StockManagementTest {

        @Test
        @DisplayName("재고를 증가시킬 수 있다")
        void increaseStock_Success() {
            // given
            Product product = Product.create("테스트 상품", null, new BigDecimal("10000"), 100);
            int increaseAmount = 50;

            // when
            product.increaseStock(increaseAmount);

            // then
            assertThat(product.getStockQuantity()).isEqualTo(150);
        }

        @Test
        @DisplayName("재고 증가량이 0 이하면 예외가 발생한다")
        void increaseStock_ZeroOrNegative_ThrowsException() {
            // given
            Product product = Product.create("테스트 상품", null, new BigDecimal("10000"), 100);

            // when & then
            assertThatThrownBy(() -> product.increaseStock(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be positive");

            assertThatThrownBy(() -> product.increaseStock(-10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be positive");
        }

        @Test
        @DisplayName("재고를 감소시킬 수 있다")
        void decreaseStock_Success() {
            // given
            Product product = Product.create("테스트 상품", null, new BigDecimal("10000"), 100);
            int decreaseAmount = 30;

            // when
            product.decreaseStock(decreaseAmount);

            // then
            assertThat(product.getStockQuantity()).isEqualTo(70);
        }

        @Test
        @DisplayName("재고 감소량이 0 이하면 예외가 발생한다")
        void decreaseStock_ZeroOrNegative_ThrowsException() {
            // given
            Product product = Product.create("테스트 상품", null, new BigDecimal("10000"), 100);

            // when & then
            assertThatThrownBy(() -> product.decreaseStock(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be positive");

            assertThatThrownBy(() -> product.decreaseStock(-10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be positive");
        }

        @Test
        @DisplayName("재고가 부족하면 감소 시 예외가 발생한다")
        void decreaseStock_InsufficientStock_ThrowsException() {
            // given
            Product product = Product.create("테스트 상품", null, new BigDecimal("10000"), 50);

            // when & then
            assertThatThrownBy(() -> product.decreaseStock(51))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Insufficient stock");
        }

        @Test
        @DisplayName("재고가 0이 되면 상태가 OUT_OF_STOCK으로 변경된다")
        void decreaseStock_BecomeOutOfStock() {
            // given
            Product product = Product.create("테스트 상품", null, new BigDecimal("10000"), 50);

            // when
            product.decreaseStock(50);

            // then
            assertThat(product.getStockQuantity()).isZero();
            assertThat(product.getStatus()).isEqualTo(ProductStatus.OUT_OF_STOCK);
        }

        @Test
        @DisplayName("품절 상태에서 재고 증가 시 ACTIVE 상태로 복구된다")
        void increaseStock_RestoreFromOutOfStock() {
            // given
            Product product = Product.create("테스트 상품", null, new BigDecimal("10000"), 10);
            product.decreaseStock(10); // 품절 상태로 만듦

            // when
            product.increaseStock(5);

            // then
            assertThat(product.getStockQuantity()).isEqualTo(5);
            assertThat(product.getStatus()).isEqualTo(ProductStatus.ACTIVE);
        }
    }

    @Nested
    @DisplayName("가격 변경 테스트")
    class PriceChangeTest {

        @Test
        @DisplayName("가격을 변경할 수 있다")
        void changePrice_Success() {
            // given
            Product product = Product.create("테스트 상품", null, new BigDecimal("10000"), 100);
            BigDecimal newPrice = new BigDecimal("15000");

            // when
            product.changePrice(newPrice);

            // then
            assertThat(product.getPrice()).isEqualByComparingTo(newPrice);
        }

        @Test
        @DisplayName("음수 가격으로 변경할 수 없다")
        void changePrice_Negative_ThrowsException() {
            // given
            Product product = Product.create("테스트 상품", null, new BigDecimal("10000"), 100);
            BigDecimal negativePrice = new BigDecimal("-5000");

            // when & then
            assertThatThrownBy(() -> product.changePrice(negativePrice))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be zero or greater");
        }

        @Test
        @DisplayName("가격을 0원으로 변경할 수 있다")
        void changePrice_Zero_Success() {
            // given
            Product product = Product.create("테스트 상품", null, new BigDecimal("10000"), 100);

            // when
            product.changePrice(BigDecimal.ZERO);

            // then
            assertThat(product.getPrice()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("상품 상태 관리 테스트")
    class StatusManagementTest {

        @Test
        @DisplayName("상품을 활성화할 수 있다")
        void activate_Success() {
            // given
            Product product = Product.create("테스트 상품", null, new BigDecimal("10000"), 100);
            product.deactivate();

            // when
            product.activate();

            // then
            assertThat(product.getStatus()).isEqualTo(ProductStatus.ACTIVE);
        }

        @Test
        @DisplayName("재고가 0인 상태에서 활성화하면 OUT_OF_STOCK 상태가 된다")
        void activate_WithZeroStock_BecomesOutOfStock() {
            // given
            Product product = Product.create("테스트 상품", null, new BigDecimal("10000"), 0);
            product.deactivate();

            // when
            product.activate();

            // then
            assertThat(product.getStatus()).isEqualTo(ProductStatus.OUT_OF_STOCK);
        }

        @Test
        @DisplayName("단종된 상품은 활성화할 수 없다")
        void activate_DiscontinuedProduct_ThrowsException() {
            // given
            Product product = Product.create("테스트 상품", null, new BigDecimal("10000"), 100);
            product.discontinue();

            // when & then
            assertThatThrownBy(() -> product.activate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot activate discontinued product");
        }

        @Test
        @DisplayName("상품을 비활성화할 수 있다")
        void deactivate_Success() {
            // given
            Product product = Product.create("테스트 상품", null, new BigDecimal("10000"), 100);

            // when
            product.deactivate();

            // then
            assertThat(product.getStatus()).isEqualTo(ProductStatus.INACTIVE);
        }

        @Test
        @DisplayName("단종된 상품은 비활성화할 수 없다")
        void deactivate_DiscontinuedProduct_ThrowsException() {
            // given
            Product product = Product.create("테스트 상품", null, new BigDecimal("10000"), 100);
            product.discontinue();

            // when & then
            assertThatThrownBy(() -> product.deactivate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot deactivate discontinued product");
        }

        @Test
        @DisplayName("상품을 단종시킬 수 있다")
        void discontinue_Success() {
            // given
            Product product = Product.create("테스트 상품", null, new BigDecimal("10000"), 100);

            // when
            product.discontinue();

            // then
            assertThat(product.getStatus()).isEqualTo(ProductStatus.DISCONTINUED);
            assertThat(product.isDiscontinued()).isTrue();
        }
    }

    @Nested
    @DisplayName("상품 정보 수정 테스트")
    class UpdateInfoTest {

        @Test
        @DisplayName("상품명과 설명을 수정할 수 있다")
        void updateInfo_Success() {
            // given
            Product product = Product.create("원래 상품명", "원래 설명", new BigDecimal("10000"), 100);
            String newName = "새로운 상품명";
            String newDescription = "새로운 설명";

            // when
            product.updateInfo(newName, newDescription);

            // then
            assertThat(product.getName()).isEqualTo(newName);
            assertThat(product.getDescription()).isEqualTo(newDescription);
        }

        @Test
        @DisplayName("상품명만 수정할 수 있다")
        void updateInfo_NameOnly_Success() {
            // given
            Product product = Product.create("원래 상품명", "원래 설명", new BigDecimal("10000"), 100);
            String newName = "새로운 상품명";

            // when
            product.updateInfo(newName, null);

            // then
            assertThat(product.getName()).isEqualTo(newName);
            assertThat(product.getDescription()).isEqualTo("원래 설명");
        }

        @Test
        @DisplayName("잘못된 상품명으로 수정 시 예외가 발생한다")
        void updateInfo_InvalidName_ThrowsException() {
            // given
            Product product = Product.create("원래 상품명", "원래 설명", new BigDecimal("10000"), 100);
            String invalidName = "a".repeat(201);

            // when & then
            assertThatThrownBy(() -> product.updateInfo(invalidName, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not exceed 200 characters");
        }
    }

    @Nested
    @DisplayName("판매 가능 여부 확인 테스트")
    class AvailabilityTest {

        @Test
        @DisplayName("ACTIVE 상태이고 재고가 있으면 판매 가능하다")
        void isAvailableForSale_ActiveWithStock_ReturnsTrue() {
            // given
            Product product = Product.create("테스트 상품", null, new BigDecimal("10000"), 100);

            // when & then
            assertThat(product.isAvailableForSale()).isTrue();
            assertThat(product.hasStock()).isTrue();
            assertThat(product.isActive()).isTrue();
        }

        @Test
        @DisplayName("ACTIVE 상태이지만 재고가 0이면 판매 불가능하다")
        void isAvailableForSale_ActiveWithoutStock_ReturnsFalse() {
            // given
            Product product = Product.create("테스트 상품", null, new BigDecimal("10000"), 0);

            // when & then
            assertThat(product.isAvailableForSale()).isFalse();
            assertThat(product.hasStock()).isFalse();
        }

        @Test
        @DisplayName("INACTIVE 상태이면 재고가 있어도 판매 불가능하다")
        void isAvailableForSale_Inactive_ReturnsFalse() {
            // given
            Product product = Product.create("테스트 상품", null, new BigDecimal("10000"), 100);
            product.deactivate();

            // when & then
            assertThat(product.isAvailableForSale()).isFalse();
            assertThat(product.hasStock()).isTrue();
            assertThat(product.isActive()).isFalse();
        }

        @Test
        @DisplayName("OUT_OF_STOCK 상태이면 판매 불가능하다")
        void isAvailableForSale_OutOfStock_ReturnsFalse() {
            // given
            Product product = Product.create("테스트 상품", null, new BigDecimal("10000"), 10);
            product.decreaseStock(10);

            // when & then
            assertThat(product.isAvailableForSale()).isFalse();
            assertThat(product.getStatus()).isEqualTo(ProductStatus.OUT_OF_STOCK);
        }

        @Test
        @DisplayName("단종된 상품은 판매 불가능하다")
        void isAvailableForSale_Discontinued_ReturnsFalse() {
            // given
            Product product = Product.create("테스트 상품", null, new BigDecimal("10000"), 100);
            product.discontinue();

            // when & then
            assertThat(product.isAvailableForSale()).isFalse();
            assertThat(product.isDiscontinued()).isTrue();
        }
    }
}
