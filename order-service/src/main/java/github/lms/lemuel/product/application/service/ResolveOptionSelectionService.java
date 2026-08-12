package github.lms.lemuel.product.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.product.application.port.in.ResolveOptionSelectionUseCase;
import github.lms.lemuel.product.application.port.out.LoadOptionCatalogPort;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.domain.*;
import github.lms.lemuel.product.domain.OptionSignature.AxisSelection;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 옵션 선택 → SKU(variant) 변환.
 *
 * <p><b>기본 경로는 카탈로그 + 조합 서명</b>이다. 상품이 채택한 축({@code product_option_axes})으로 선택을
 * 검증하고, {@link OptionSignature} 를 계산해 {@code (product_id, option_signature)} 유니크 인덱스로 SKU 를
 * 단건 조회한다. 이전에는 {@code "색상:빨강/사이즈:L"} 문자열을 조립해 상품의 전체 SKU 를 선형 스캔했다 —
 * 구분자·순서·공백 중 하나만 어긋나도 조회가 조용히 실패하는 구조였고, SKU 수에 비례해 느려졌다.
 *
 * <p><b>레거시 경로(옵션 트리 JSON + option_name 스캔)는 두 경우에만 쓰인다</b>:
 * <ol>
 *   <li>상품이 아직 카탈로그로 백필되지 않았다(채택한 축이 하나도 없다)</li>
 *   <li>카탈로그 검증은 통과했는데 그 조합의 SKU 에 아직 서명이 없다</li>
 * </ol>
 * 둘 다 이관 중에만 존재하는 상태다. 서명이 채워진 SKU 는 절대 이 경로로 내려오지 않으므로,
 * 백필이 끝나면(모든 SKU 가 서명 보유) 이 다리를 걷어낼 수 있다.
 *
 * <p>선택 <b>순서</b>는 카탈로그 경로에서 의미가 없다 — 서명이 축 id 로 정렬되기 때문이다. 반면 레거시
 * 경로는 트리 차수 순서를 그대로 요구한다. 이 차이는 의도된 것이며, 순서 의존이 사라지는 게 이관의 이득이다.
 *
 * <p>Jackson 빈 주입 트랩(Boot4 의 제한 스캔/ObjectMapper 빈 부재)을 피하려고 파싱 전용 ObjectMapper 를
 * 로컬 상수로 보유한다 — 읽기 전용 트리 파싱만 하므로 설정 의존이 없다.
 */
@Service
@Transactional(readOnly = true)
public class ResolveOptionSelectionService implements ResolveOptionSelectionUseCase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SEP = "/";

    private final LoadProductPort loadProductPort;
    private final LoadProductVariantPort loadVariantPort;
    private final LoadOptionCatalogPort loadCatalogPort;

    public ResolveOptionSelectionService(LoadProductPort loadProductPort,
                                         LoadProductVariantPort loadVariantPort,
                                         LoadOptionCatalogPort loadCatalogPort) {
        this.loadProductPort = loadProductPort;
        this.loadVariantPort = loadVariantPort;
        this.loadCatalogPort = loadCatalogPort;
    }

    @Override
    public ProductVariant resolve(Long productId, List<Selection> selections) {
        if (selections == null || selections.isEmpty()) {
            throw new ProductInvariantViolationException("옵션 선택이 비어 있습니다");
        }

        List<ProductOptionAxis> productAxes = loadCatalogPort.loadProductAxes(productId);
        if (!productAxes.isEmpty()) {
            String signature = OptionSignature.of(validateAgainstCatalog(productAxes, selections));
            Optional<ProductVariant> found = loadVariantPort.loadByOptionSignature(productId, signature);
            if (found.isPresent()) {
                return found.get();
            }
            // 서명 미부여 SKU — 이관 중에만 발생한다. 아래 레거시 조회로 내려간다.
        }

        return resolveFromLegacyTree(productId, selections);
    }

    // --- 카탈로그 경로 -------------------------------------------------------

    /**
     * 선택을 상품 카탈로그로 검증하고 서명 입력을 만든다.
     *
     * <p>검증은 <b>집합</b> 기준이다: 상품이 필수로 요구하는 축을 빠짐없이, 각 축을 정확히 한 번,
     * 상품이 노출 중인 값으로만 골라야 한다. 순서는 보지 않는다.
     */
    private List<AxisSelection> validateAgainstCatalog(List<ProductOptionAxis> productAxes,
                                                       List<Selection> selections) {
        List<AxisSelection> resolved = new ArrayList<>(selections.size());
        Set<Long> chosenAxisIds = new LinkedHashSet<>();

        for (Selection selection : selections) {
            OptionAxis axis = loadCatalogPort
                    .findAxisByCode(OptionCode.fromDisplayName(selection.name(), "옵션명"))
                    .orElseThrow(() -> new ProductInvariantViolationException(
                            "존재하지 않는 옵션 축: " + selection.name()));

            ProductOptionAxis productAxis = productAxes.stream()
                    .filter(a -> a.getAxisId().equals(axis.getId()))
                    .findFirst()
                    .orElseThrow(() -> new ProductInvariantViolationException(
                            "이 상품이 취급하지 않는 옵션 축: " + selection.name()));

            if (!chosenAxisIds.add(axis.getId())) {
                throw new ProductInvariantViolationException(
                        "같은 옵션 축을 두 번 선택했습니다: " + selection.name());
            }

            OptionAxisValue axisValue = loadCatalogPort
                    .findAxisValueByCode(axis.getId(),
                            OptionCode.fromDisplayName(selection.value(), "선택값"))
                    .orElseThrow(() -> new ProductInvariantViolationException(
                            "존재하지 않는 옵션 값: " + selection.name() + "=" + selection.value()));

            ProductOptionValue productValue = loadCatalogPort
                    .findProductValue(productAxis.getId(), axisValue.getId())
                    .orElseThrow(() -> new ProductInvariantViolationException(
                            "이 상품이 판매하지 않는 옵션 값: " + selection.name() + "=" + selection.value()));

            if (!productValue.isActive()) {
                throw new ProductInvariantViolationException(
                        "현재 선택할 수 없는 옵션 값입니다: " + selection.name() + "=" + selection.value());
            }

            resolved.add(new AxisSelection(axis.getId(), axisValue.getId()));
        }

        String missing = productAxes.stream()
                .filter(ProductOptionAxis::isRequired)
                .filter(a -> !chosenAxisIds.contains(a.getAxisId()))
                .map(a -> loadCatalogPort.findAxisById(a.getAxisId())
                        .map(OptionAxis::getName)
                        .orElse("axisId=" + a.getAxisId()))
                .collect(Collectors.joining(", "));
        if (!missing.isEmpty()) {
            throw new ProductInvariantViolationException("옵션 선택이 불완전합니다 — 필수 축 미선택: " + missing);
        }

        return resolved;
    }

    // --- 레거시 경로(이관 중 한시) -------------------------------------------

    private ProductVariant resolveFromLegacyTree(Long productId, List<Selection> selections) {
        Product product = loadProductPort.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        if (product.getOptionsJson() == null || product.getOptionsJson().isBlank()) {
            throw new ProductInvariantViolationException("옵션 트리가 정의되지 않은 상품입니다: productId=" + productId);
        }

        validatePathExists(parse(product.getOptionsJson()), selections);

        String optionName = selections.stream()
                .map(s -> s.name() + ":" + s.value())
                .collect(Collectors.joining(SEP));

        return loadVariantPort.loadByProductId(productId).stream()
                .filter(v -> optionName.equals(v.getOptionName()))
                .findFirst()
                .orElseThrow(() -> new ProductInvariantViolationException(
                        "선택한 옵션 조합에 대응하는 SKU 가 없습니다: " + optionName));
    }

    private JsonNode parse(String optionsJson) {
        try {
            return MAPPER.readTree(optionsJson);
        } catch (Exception e) {
            throw new ProductInvariantViolationException("옵션 트리 JSON 파싱 실패", e);
        }
    }

    /**
     * 선택 경로가 트리에 실재하는지 차수별로 검증한다. 차수 이름 불일치 / 없는 값 / 선택 불완전(leaf 미도달) /
     * 선택 과다(leaf 이후 추가 선택) 를 모두 거른다.
     */
    private void validatePathExists(JsonNode root, List<Selection> selections) {
        JsonNode level = root;
        for (Selection sel : selections) {
            if (level == null || !level.hasNonNull("name") || !level.has("values")) {
                throw new ProductInvariantViolationException("선택 차수가 트리보다 많습니다: " + sel.name());
            }
            String levelName = level.get("name").asText();
            if (!levelName.equals(sel.name())) {
                throw new ProductInvariantViolationException(
                        "옵션 차수 이름 불일치: 기대=" + levelName + ", 입력=" + sel.name());
            }
            JsonNode matched = null;
            for (JsonNode valueNode : level.get("values")) {
                if (valueNode.hasNonNull("value") && valueNode.get("value").asText().equals(sel.value())) {
                    matched = valueNode;
                    break;
                }
            }
            if (matched == null) {
                throw new ProductInvariantViolationException(
                        "존재하지 않는 옵션 값: " + sel.name() + "=" + sel.value());
            }
            level = matched.get("children"); // leaf 면 null
        }
        if (level != null && level.has("values")) {
            throw new ProductInvariantViolationException(
                    "옵션 선택이 불완전합니다 — 더 깊은 차수(" + level.get("name").asText() + ") 선택이 필요합니다");
        }
    }
}
