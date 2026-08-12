package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.ResolveOptionSelectionUseCase.Selection;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.service.OptionCatalogFakes.FakeOptionCatalogPort;
import github.lms.lemuel.product.application.service.OptionCatalogFakes.FakeProductVariantPort;
import github.lms.lemuel.product.application.service.OptionCatalogFakes.FakeVariantOptionMappingPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductOptionAxis;
import github.lms.lemuel.product.domain.ProductOptionValue;
import github.lms.lemuel.product.domain.ProductVariant;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 카탈로그 + 조합 서명 경로 검증 (설계 Phase 3).
 *
 * <p>레거시 트리 경로는 {@link ResolveOptionSelectionServiceTest} 가 담당한다. 여기서는 백필이 끝난
 * 상품에서 <b>서명 단건 조회</b>가 실제로 쓰이는지, 그리고 선택 순서 의존이 사라졌는지를 본다.
 */
@DisplayName("ResolveOptionSelectionService — 카탈로그·서명 경로")
class ResolveOptionSelectionCatalogPathTest {

    private static final Long PRODUCT_ID = 1L;

    private static final String TREE = """
            {
              "name": "색상",
              "values": [
                { "value": "빨강", "children": { "name": "사이즈", "values": [ {"value":"L"}, {"value":"M"} ] } },
                { "value": "파랑", "children": { "name": "사이즈", "values": [ {"value":"L"} ] } }
              ]
            }
            """;

    private FakeProductVariantPort variantPort;
    private FakeOptionCatalogPort catalogPort;
    private LoadProductPort loadProductPort;
    private ResolveOptionSelectionService service;

    @BeforeEach
    void setUp() {
        variantPort = new FakeProductVariantPort();
        catalogPort = new FakeOptionCatalogPort();
        loadProductPort = mock(LoadProductPort.class);
        service = new ResolveOptionSelectionService(loadProductPort, variantPort, catalogPort);
    }

    /** 실제 이관 순서대로 카탈로그·서명을 채운 상품을 만든다. */
    private void backfilledProduct(String... optionNames) {
        for (int i = 0; i < optionNames.length; i++) {
            variantPort.add(PRODUCT_ID, "SKU-" + i, optionNames[i]);
        }
        new BackfillOptionCatalogService(variantPort, catalogPort, catalogPort).backfillAll();
        new BackfillVariantSignatureService(variantPort, variantPort, catalogPort,
                new FakeVariantOptionMappingPort()).backfillAll();
    }

    @Nested
    @DisplayName("서명 단건 조회")
    class SignatureLookup {

        @Test
        @DisplayName("백필된 상품은 서명으로 SKU 를 찾는다")
        void resolvesBySignature() {
            backfilledProduct("색상:빨강/사이즈:L", "색상:빨강/사이즈:M");

            ProductVariant result = service.resolve(PRODUCT_ID,
                    List.of(new Selection("색상", "빨강"), new Selection("사이즈", "L")));

            assertThat(result.getOptionName()).isEqualTo("색상:빨강/사이즈:L");
            assertThat(result.hasOptionSignature()).isTrue();
        }

        @Test
        @DisplayName("선택 순서가 달라도 같은 SKU 를 찾는다 — 문자열 조립 시절엔 불가능했다")
        void orderIndependent() {
            backfilledProduct("색상:빨강/사이즈:L");

            ProductVariant reversed = service.resolve(PRODUCT_ID,
                    List.of(new Selection("사이즈", "L"), new Selection("색상", "빨강")));

            assertThat(reversed.getSku()).isEqualTo("SKU-0");
        }

        @Test
        @DisplayName("레거시 트리(options_json)를 읽지 않는다 — 상품 조회 자체가 일어나지 않는다")
        void doesNotTouchOptionsJson() {
            backfilledProduct("색상:빨강/사이즈:L");

            service.resolve(PRODUCT_ID,
                    List.of(new Selection("색상", "빨강"), new Selection("사이즈", "L")));

            // loadProductPort 는 스텁하지 않았다 — 호출됐다면 NPE 나 빈 Optional 로 실패했을 것이다.
            assertThat(catalogPort.loadProductAxes(PRODUCT_ID)).hasSize(2);
        }
    }

    @Nested
    @DisplayName("선택 검증")
    class Validation {

        @Test
        @DisplayName("필수 축을 빠뜨리면 거부한다")
        void rejectsMissingRequiredAxis() {
            backfilledProduct("색상:빨강/사이즈:L");

            assertThatThrownBy(() -> service.resolve(PRODUCT_ID, List.of(new Selection("색상", "빨강"))))
                    .isInstanceOf(ProductInvariantViolationException.class)
                    .hasMessageContaining("불완전")
                    .hasMessageContaining("사이즈");
        }

        @Test
        @DisplayName("같은 축을 두 번 고르면 거부한다")
        void rejectsDuplicateAxis() {
            backfilledProduct("색상:빨강/사이즈:L", "색상:파랑/사이즈:L");

            assertThatThrownBy(() -> service.resolve(PRODUCT_ID, List.of(
                    new Selection("색상", "빨강"), new Selection("색상", "파랑"),
                    new Selection("사이즈", "L"))))
                    .isInstanceOf(ProductInvariantViolationException.class)
                    .hasMessageContaining("두 번");
        }

        @Test
        @DisplayName("존재하지 않는 축은 거부한다")
        void rejectsUnknownAxis() {
            backfilledProduct("색상:빨강/사이즈:L");

            assertThatThrownBy(() -> service.resolve(PRODUCT_ID, List.of(
                    new Selection("각인", "AB"), new Selection("색상", "빨강"),
                    new Selection("사이즈", "L"))))
                    .isInstanceOf(ProductInvariantViolationException.class)
                    .hasMessageContaining("존재하지 않는 옵션 축");
        }

        @Test
        @DisplayName("존재하지 않는 값은 거부한다")
        void rejectsUnknownValue() {
            backfilledProduct("색상:빨강/사이즈:L");

            assertThatThrownBy(() -> service.resolve(PRODUCT_ID, List.of(
                    new Selection("색상", "초록"), new Selection("사이즈", "L"))))
                    .isInstanceOf(ProductInvariantViolationException.class)
                    .hasMessageContaining("존재하지 않는 옵션 값");
        }

        @Test
        @DisplayName("노출 중단된 값은 거부한다 — 값 삭제가 아니라 신규 선택 차단이다")
        void rejectsInactiveValue() {
            backfilledProduct("색상:빨강/사이즈:L");
            ProductOptionAxis colorAxis = catalogPort.loadProductAxes(PRODUCT_ID).get(0);
            ProductOptionValue red = catalogPort.loadProductValues(colorAxis.getId()).get(0);
            red.deactivate();
            catalogPort.saveProductValue(red);

            assertThatThrownBy(() -> service.resolve(PRODUCT_ID, List.of(
                    new Selection("색상", "빨강"), new Selection("사이즈", "L"))))
                    .isInstanceOf(ProductInvariantViolationException.class)
                    .hasMessageContaining("선택할 수 없는");
        }

        @Test
        @DisplayName("빈 선택은 거부한다")
        void rejectsEmptySelection() {
            backfilledProduct("색상:빨강/사이즈:L");

            assertThatThrownBy(() -> service.resolve(PRODUCT_ID, List.of()))
                    .isInstanceOf(ProductInvariantViolationException.class)
                    .hasMessageContaining("비어");
        }
    }

    @Nested
    @DisplayName("이관 중 폴백")
    class MigrationFallback {

        @Test
        @DisplayName("카탈로그 검증은 통과했지만 서명이 없는 SKU 는 레거시 조회로 찾는다")
        void fallsBackForUnsignedVariant() {
            backfilledProduct("색상:빨강/사이즈:L");
            // 서명이 아직 없는 SKU 를 나중에 추가된 것처럼 끼워 넣는다.
            variantPort.add(PRODUCT_ID, "SKU-LATE", "색상:빨강/사이즈:M");
            new BackfillOptionCatalogService(variantPort, catalogPort, catalogPort).backfillAll();
            when(loadProductPort.findById(PRODUCT_ID)).thenReturn(Optional.of(productWithTree()));

            ProductVariant result = service.resolve(PRODUCT_ID, List.of(
                    new Selection("색상", "빨강"), new Selection("사이즈", "M")));

            assertThat(result.getSku()).isEqualTo("SKU-LATE");
            assertThat(result.hasOptionSignature()).isFalse();
        }

        @Test
        @DisplayName("카탈로그가 없는 상품은 처음부터 레거시 트리로 간다")
        void usesLegacyWhenNoCatalog() {
            variantPort.add(PRODUCT_ID, "SKU-0", "색상:빨강/사이즈:L");
            when(loadProductPort.findById(PRODUCT_ID)).thenReturn(Optional.of(productWithTree()));

            ProductVariant result = service.resolve(PRODUCT_ID, List.of(
                    new Selection("색상", "빨강"), new Selection("사이즈", "L")));

            assertThat(result.getSku()).isEqualTo("SKU-0");
        }

        private Product productWithTree() {
            return Product.create("상품", "설명", new BigDecimal("10000"), 100, TREE);
        }
    }
}
