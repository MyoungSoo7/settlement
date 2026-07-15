package github.lms.lemuel.product.application.service;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

import github.lms.lemuel.product.application.port.in.ResolveOptionSelectionUseCase.Selection;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductVariant;
import github.lms.lemuel.product.domain.ProductVariantStatus;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * 옵션 트리(JSON) → SKU 변환 단위 테스트. 트리 경로 검증 분기와 optionName 조립·SKU 매칭을 격리 검증한다.
 */
class ResolveOptionSelectionServiceTest {

    private static final String TREE = """
            {
              "name": "색상",
              "values": [
                { "value": "빨강", "children": { "name": "사이즈", "values": [ {"value":"S"}, {"value":"L"} ] } },
                { "value": "파랑", "children": { "name": "사이즈", "values": [ {"value":"M"} ] } }
              ]
            }
            """;

    private LoadProductPort loadProductPort;
    private LoadProductVariantPort loadVariantPort;
    private ResolveOptionSelectionService service;

    @BeforeEach
    void setup() {
        loadProductPort = mock(LoadProductPort.class);
        loadVariantPort = mock(LoadProductVariantPort.class);
        service = new ResolveOptionSelectionService(loadProductPort, loadVariantPort);
    }

    private void productWithTree(String optionsJson) {
        Product product = Product.create("상품", "설명", new BigDecimal("10000"), 100, optionsJson);
        when(loadProductPort.findById(100L)).thenReturn(Optional.of(product));
    }

    private ProductVariant variant(String optionName) {
        return ProductVariant.rehydrate(7L, 100L, "SKU-RED-L", optionName,
                BigDecimal.ZERO, 10, 0L, ProductVariantStatus.ACTIVE, null, null);
    }

    @Test
    @DisplayName("유효 경로: 트리 검증 후 optionName 조립 → 대응 SKU 반환")
    void resolve_validPath() {
        productWithTree(TREE);
        when(loadVariantPort.loadByProductId(100L))
                .thenReturn(List.of(variant("색상:빨강/사이즈:L")));

        ProductVariant result = service.resolve(100L,
                List.of(new Selection("색상", "빨강"), new Selection("사이즈", "L")));

        assertThat(result.getOptionName()).isEqualTo("색상:빨강/사이즈:L");
        assertThat(result.getId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("없는 옵션 값 → IllegalArgumentException")
    void resolve_unknownValue() {
        productWithTree(TREE);
        assertThatThrownBy(() -> service.resolve(100L,
                List.of(new Selection("색상", "초록"))))
                .isInstanceOf(ProductInvariantViolationException.class)
                .hasMessageContaining("존재하지 않는 옵션 값");
    }

    @Test
    @DisplayName("차수 이름 불일치 → IllegalArgumentException")
    void resolve_levelNameMismatch() {
        productWithTree(TREE);
        assertThatThrownBy(() -> service.resolve(100L,
                List.of(new Selection("사이즈", "빨강"))))
                .isInstanceOf(ProductInvariantViolationException.class)
                .hasMessageContaining("차수 이름 불일치");
    }

    @Test
    @DisplayName("선택 불완전(leaf 미도달) → IllegalArgumentException")
    void resolve_incompletePath() {
        productWithTree(TREE);
        assertThatThrownBy(() -> service.resolve(100L,
                List.of(new Selection("색상", "빨강"))))
                .isInstanceOf(ProductInvariantViolationException.class)
                .hasMessageContaining("불완전");
    }

    @Test
    @DisplayName("선택 과다(leaf 이후 추가 차수) → IllegalArgumentException")
    void resolve_tooManySelections() {
        productWithTree(TREE);
        assertThatThrownBy(() -> service.resolve(100L,
                List.of(new Selection("색상", "빨강"), new Selection("사이즈", "L"),
                        new Selection("각인", "있음"))))
                .isInstanceOf(ProductInvariantViolationException.class)
                .hasMessageContaining("선택 차수가 트리보다 많습니다");
    }

    @Test
    @DisplayName("옵션 트리 미정의 상품 → IllegalArgumentException")
    void resolve_noOptionsJson() {
        productWithTree(null);
        assertThatThrownBy(() -> service.resolve(100L, List.of(new Selection("색상", "빨강"))))
                .isInstanceOf(ProductInvariantViolationException.class)
                .hasMessageContaining("옵션 트리가 정의되지 않은");
    }

    @Test
    @DisplayName("경로는 유효하나 대응 SKU 부재 → IllegalArgumentException")
    void resolve_noMatchingVariant() {
        productWithTree(TREE);
        when(loadVariantPort.loadByProductId(100L))
                .thenReturn(List.of(variant("색상:파랑/사이즈:M")));

        assertThatThrownBy(() -> service.resolve(100L,
                List.of(new Selection("색상", "빨강"), new Selection("사이즈", "L"))))
                .isInstanceOf(ProductInvariantViolationException.class)
                .hasMessageContaining("대응하는 SKU 가 없습니다");
    }

    @Test
    @DisplayName("상품 미존재 → ProductNotFoundException")
    void resolve_productNotFound() {
        when(loadProductPort.findById(100L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolve(100L, List.of(new Selection("색상", "빨강"))))
                .isInstanceOf(ProductNotFoundException.class);
    }
}
