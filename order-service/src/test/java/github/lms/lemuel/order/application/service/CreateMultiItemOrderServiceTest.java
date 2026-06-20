package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.in.CreateMultiItemOrderUseCase;
import github.lms.lemuel.order.application.port.out.LoadUserForOrderPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPort;
import github.lms.lemuel.order.application.port.out.SendOrderNotificationPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.exception.UserNotExistsException;
import github.lms.lemuel.product.application.port.in.DecreaseVariantStockUseCase;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductVariant;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class CreateMultiItemOrderServiceTest {

    @Mock LoadUserForOrderPort loadUserPort;
    @Mock LoadProductPort loadProductPort;
    @Mock LoadProductVariantPort loadVariantPort;
    @Mock DecreaseVariantStockUseCase decreaseStockUseCase;
    @Mock SaveOrderPort saveOrderPort;
    @Mock SendOrderNotificationPort sendNotificationPort;
    @InjectMocks CreateMultiItemOrderService service;

    private Product mockProduct(Long id, String name, BigDecimal price) {
        Product p = Product.create(name, "설명", price, 100);
        // use reflection or mock
        Product spy = spy(p);
        when(spy.getId()).thenReturn(id);
        return spy;
    }

    @Test @DisplayName("create: 상품만 있는 주문 성공 (variant 없음)")
    void create_noVariant() {
        when(loadUserPort.findEmailById(1L)).thenReturn(Optional.of("user@test.com"));
        Product product = mockProduct(10L, "상품A", new BigDecimal("10000"));
        when(loadProductPort.findById(10L)).thenReturn(Optional.of(product));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var lines = List.of(new CreateMultiItemOrderUseCase.Line(10L, null, 2));
        Order result = service.create(1L, lines);
        assertThat(result).isNotNull();
        assertThat(result.getItems()).hasSize(1);
        verify(decreaseStockUseCase, never()).decrease(any(), anyInt());
        verify(sendNotificationPort).sendOrderConfirmation(eq("user@test.com"), any());
    }

    @Test @DisplayName("create: SKU 있는 주문 — 재고 차감")
    void create_withVariant() {
        when(loadUserPort.findEmailById(1L)).thenReturn(Optional.of("user@test.com"));
        Product product = mockProduct(10L, "상품A", new BigDecimal("10000"));
        when(loadProductPort.findById(10L)).thenReturn(Optional.of(product));

        ProductVariant variant = ProductVariant.create(10L, "SKU-001", "빨강", new BigDecimal("1000"), 50);
        ProductVariant variantSpy = spy(variant);
        when(variantSpy.getProductId()).thenReturn(10L);
        when(loadVariantPort.loadById(20L)).thenReturn(Optional.of(variantSpy));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var lines = List.of(new CreateMultiItemOrderUseCase.Line(10L, 20L, 1));
        Order result = service.create(1L, lines);
        assertThat(result).isNotNull();
        verify(decreaseStockUseCase).decrease(20L, 1);
    }

    @Test @DisplayName("create: 사용자 없으면 예외")
    void create_userNotFound() {
        when(loadUserPort.findEmailById(99L)).thenReturn(Optional.empty());
        var lines = List.of(new CreateMultiItemOrderUseCase.Line(10L, null, 1));
        assertThatThrownBy(() -> service.create(99L, lines))
                .isInstanceOf(UserNotExistsException.class);
    }

    @Test @DisplayName("create: 상품 없으면 예외")
    void create_productNotFound() {
        when(loadUserPort.findEmailById(1L)).thenReturn(Optional.of("user@test.com"));
        when(loadProductPort.findById(10L)).thenReturn(Optional.empty());
        var lines = List.of(new CreateMultiItemOrderUseCase.Line(10L, null, 1));
        assertThatThrownBy(() -> service.create(1L, lines))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test @DisplayName("create: variant가 product에 속하지 않으면 예외")
    void create_variantMismatch() {
        when(loadUserPort.findEmailById(1L)).thenReturn(Optional.of("user@test.com"));
        Product product = mockProduct(10L, "상품A", new BigDecimal("10000"));
        when(loadProductPort.findById(10L)).thenReturn(Optional.of(product));

        ProductVariant variant = ProductVariant.create(99L, "SKU", "opt", BigDecimal.ZERO, 10);
        ProductVariant variantSpy = spy(variant);
        when(variantSpy.getProductId()).thenReturn(99L); // different product
        when(loadVariantPort.loadById(20L)).thenReturn(Optional.of(variantSpy));

        var lines = List.of(new CreateMultiItemOrderUseCase.Line(10L, 20L, 1));
        assertThatThrownBy(() -> service.create(1L, lines))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("variant 가 product 에 속하지 않음");
    }
}
