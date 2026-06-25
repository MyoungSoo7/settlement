package github.lms.lemuel.product.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.product.application.port.in.ResolveOptionSelectionUseCase;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductVariant;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 옵션 트리(JSON) → SKU(variant) 변환 서비스.
 *
 * <p>상품의 {@code options_json} 은 임의 깊이의 옵션 트리를 다음 형태로 보관한다(차수마다 name + values,
 * 각 value 는 선택적으로 하위 차수 children 을 가진다):
 * <pre>
 * {
 *   "name": "색상",
 *   "values": [
 *     { "value": "빨강", "children": { "name": "사이즈", "values": [ {"value":"S"}, {"value":"L"} ] } },
 *     { "value": "파랑", "children": { "name": "사이즈", "values": [ {"value":"M"} ] } }
 *   ]
 * }
 * </pre>
 * 선택 경로 [(색상,빨강),(사이즈,L)] 를 트리를 따라 검증한 뒤, leaf 까지 도달하면
 * {@code product_variants.option_name} 규약("색상:빨강/사이즈:L")으로 조립해 해당 SKU 를 찾는다.
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

    public ResolveOptionSelectionService(LoadProductPort loadProductPort,
                                         LoadProductVariantPort loadVariantPort) {
        this.loadProductPort = loadProductPort;
        this.loadVariantPort = loadVariantPort;
    }

    @Override
    public ProductVariant resolve(Long productId, List<Selection> selections) {
        if (selections == null || selections.isEmpty()) {
            throw new IllegalArgumentException("옵션 선택이 비어 있습니다");
        }
        Product product = loadProductPort.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        if (product.getOptionsJson() == null || product.getOptionsJson().isBlank()) {
            throw new IllegalArgumentException("옵션 트리가 정의되지 않은 상품입니다: productId=" + productId);
        }

        validatePathExists(parse(product.getOptionsJson()), selections);

        String optionName = selections.stream()
                .map(s -> s.name() + ":" + s.value())
                .collect(Collectors.joining(SEP));

        return loadVariantPort.loadByProductId(productId).stream()
                .filter(v -> optionName.equals(v.getOptionName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "선택한 옵션 조합에 대응하는 SKU 가 없습니다: " + optionName));
    }

    private JsonNode parse(String optionsJson) {
        try {
            return MAPPER.readTree(optionsJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("옵션 트리 JSON 파싱 실패", e);
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
                throw new IllegalArgumentException("선택 차수가 트리보다 많습니다: " + sel.name());
            }
            String levelName = level.get("name").asText();
            if (!levelName.equals(sel.name())) {
                throw new IllegalArgumentException(
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
                throw new IllegalArgumentException(
                        "존재하지 않는 옵션 값: " + sel.name() + "=" + sel.value());
            }
            level = matched.get("children"); // leaf 면 null
        }
        if (level != null && level.has("values")) {
            throw new IllegalArgumentException(
                    "옵션 선택이 불완전합니다 — 더 깊은 차수(" + level.get("name").asText() + ") 선택이 필요합니다");
        }
    }
}
