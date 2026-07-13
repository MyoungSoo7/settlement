package github.lms.lemuel.product.application.service;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.application.port.out.SaveProductVariantPort;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductVariantServiceTest {

    @Mock LoadProductPort loadProductPort;
    @Mock LoadProductVariantPort loadVariantPort;
    @Mock SaveProductVariantPort saveVariantPort;
    @InjectMocks ProductVariantService service;

    @Test @DisplayName("create: SKU 생성 성공")
    void create_success() {
        when(loadProductPort.findById(1L)).thenReturn(Optional.of(mock(Product.class)));
        when(loadVariantPort.loadBySku("SKU-001")).thenReturn(Optional.empty());
        when(saveVariantPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ProductVariant result = service.create(1L, "SKU-001", "빨강/L", BigDecimal.ZERO, 50);
        assertThat(result.getSku()).isEqualTo("SKU-001");
    }

    @Test @DisplayName("create: 상품 없으면 예외")
    void create_productNotFound() {
        when(loadProductPort.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(1L, "SKU", "opt", BigDecimal.ZERO, 10))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test @DisplayName("create: SKU 중복이면 예외")
    void create_duplicateSku() {
        when(loadProductPort.findById(1L)).thenReturn(Optional.of(mock(Product.class)));
        when(loadVariantPort.loadBySku("DUP")).thenReturn(Optional.of(mock(ProductVariant.class)));
        assertThatThrownBy(() -> service.create(1L, "DUP", "opt", BigDecimal.ZERO, 10))
                .isInstanceOf(ProductInvariantViolationException.class)
                .hasMessageContaining("이미 사용 중인 SKU");
    }

    @Test @DisplayName("listByProductId: 상품별 SKU 목록")
    void listByProductId() {
        when(loadVariantPort.loadByProductId(1L)).thenReturn(List.of());
        assertThat(service.listByProductId(1L)).isEmpty();
    }
}
