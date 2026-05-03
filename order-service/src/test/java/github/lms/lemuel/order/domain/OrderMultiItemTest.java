package github.lms.lemuel.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderMultiItemTest {

    @Test
    @DisplayName("createMultiItem: amount 는 모든 lineAmount 의 합으로 자동 계산")
    void multiItem_autoTotalsAmount() {
        List<OrderItem> items = List.of(
                OrderItem.newItem(1L, null, null, "맥북", new BigDecimal("3000000"), 1),
                OrderItem.newItem(2L, 99L, "SKU-RED-L", "티셔츠", new BigDecimal("19000"), 2),
                OrderItem.newItem(3L, null, null, "마우스", new BigDecimal("50000"), 1)
        );

        Order order = Order.createMultiItem(100L, items);

        assertThat(order.isMultiItem()).isTrue();
        assertThat(order.getItems()).hasSize(3);
        // 3,000,000 + 38,000 + 50,000 = 3,088,000
        assertThat(order.getAmount()).isEqualByComparingTo("3088000");
        assertThat(order.getProductId()).isNull();   // 다건은 productId NULL
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("createMultiItem: 빈 리스트 → IllegalArgumentException")
    void multiItem_empty() {
        assertThatThrownBy(() -> Order.createMultiItem(1L, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Order.createMultiItem(1L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("단건 호환: create(userId, productId, amount) 는 기존대로 동작 (items 비어있음)")
    void singleItem_legacyPathStillWorks() {
        Order order = Order.create(100L, 5L, new BigDecimal("10000"));

        assertThat(order.isMultiItem()).isFalse();
        assertThat(order.getProductId()).isEqualTo(5L);
        assertThat(order.getAmount()).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("attachItemsToOrder: 부모 PK 부여 후 자식들 모두에게 같은 orderId 가 주입된다")
    void attachItems_propagatesId() {
        List<OrderItem> items = List.of(
                OrderItem.newItem(1L, null, null, "A", new BigDecimal("1000"), 1),
                OrderItem.newItem(2L, null, null, "B", new BigDecimal("2000"), 1)
        );
        Order order = Order.createMultiItem(100L, items);
        order.setId(42L);

        order.attachItemsToOrder();

        assertThat(order.getItems()).allSatisfy(i ->
                assertThat(i.getOrderId()).isEqualTo(42L));
    }
}
