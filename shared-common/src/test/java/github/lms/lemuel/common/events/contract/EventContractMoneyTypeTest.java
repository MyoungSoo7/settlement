package github.lms.lemuel.common.events.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.OutboxJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * DATA-STANDARD N5 강제 — 이벤트 계약 스키마의 <b>금액 필드는 JSON string</b> 이어야 한다.
 *
 * <p>런타임 직렬화는 {@link OutboxJson} 이 보장하지만, 계약(스키마)이 {@code "type": "number"} 로
 * 남아 있으면 신규 이벤트가 다시 number 로 새는 통로가 된다. 스키마·정본 샘플 양쪽을 텍스트로
 * 훑어 위반을 0으로 유지한다(클래스패스 비의존 소스 스캔 — PR #167 과 동일 패턴).
 */
class EventContractMoneyTypeTest {

    /** 금액으로 간주하는 필드명 — 소문자 비교. */
    private static final List<String> MONEY_SUFFIXES =
            List.of("amount", "fee", "principal", "deducted", "balance", "price");

    @Test
    @DisplayName("N5: 계약 스키마의 금액 필드 타입은 string 만 허용")
    void moneyFieldsAreStringInSchemas() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<String> violations = new ArrayList<>();

        for (Path schema : schemaFiles()) {
            JsonNode properties = mapper.readTree(Files.readString(schema)).path("properties");
            for (Iterator<Map.Entry<String, JsonNode>> it = properties.properties().iterator(); it.hasNext();) {
                Map.Entry<String, JsonNode> field = it.next();
                if (!isMoney(field.getKey())) {
                    continue;
                }
                String type = field.getValue().path("type").asText("");
                if (!"string".equals(type)) {
                    violations.add(schema.getFileName() + " → " + field.getKey() + ": \"" + type + "\"");
                }
            }
        }

        if (!violations.isEmpty()) {
            fail("N5 위반 — 금액 필드는 JSON string(BigDecimal.toPlainString) 이어야 함"
                    + " (~/wiki/DATA-STANDARD.md N5)\n  " + String.join("\n  ", violations));
        }
    }

    @Test
    @DisplayName("N5: 정본 샘플의 금액 값도 문자열이어야 한다(스키마와 동기)")
    void moneyValuesAreStringInSamples() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<String> violations = new ArrayList<>();

        for (Path sample : sampleFiles()) {
            JsonNode root = mapper.readTree(Files.readString(sample));
            for (Iterator<Map.Entry<String, JsonNode>> it = root.properties().iterator(); it.hasNext();) {
                Map.Entry<String, JsonNode> field = it.next();
                if (isMoney(field.getKey()) && field.getValue().isNumber()) {
                    violations.add(sample.getFileName() + " → " + field.getKey()
                            + ": " + field.getValue());
                }
            }
        }

        if (!violations.isEmpty()) {
            fail("N5 위반 — 정본 샘플의 금액은 문자열이어야 함\n  " + String.join("\n  ", violations));
        }
    }

    @Test
    @DisplayName("N5: 스키마 설명문이 'JSON number' 라고 거짓말하지 않는다")
    void descriptionsDoNotClaimNumberMoney() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path schema : schemaFiles()) {
            if (Files.readString(schema).contains("JSON number")) {
                violations.add(schema.getFileName().toString());
            }
        }
        if (!violations.isEmpty()) {
            fail("계약 설명문이 스키마와 어긋남 — 금액은 JSON string 이다\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    @DisplayName("스캔 대상이 실제로 존재한다(빈 검사로 통과하는 vacuous pass 방지)")
    void scanIsNotVacuous() throws IOException {
        assertThat(schemaFiles()).hasSizeGreaterThan(10);
        assertThat(sampleFiles()).hasSizeGreaterThan(10);
    }

    private static boolean isMoney(String field) {
        String lower = field.toLowerCase(Locale.ROOT);
        return MONEY_SUFFIXES.stream().anyMatch(lower::endsWith);
    }

    private static List<Path> schemaFiles() throws IOException {
        return filesUnder("src/testFixtures/resources/contracts/events", ".schema.json");
    }

    private static List<Path> sampleFiles() throws IOException {
        return filesUnder("src/testFixtures/resources/contracts/events/samples", ".sample.json");
    }

    private static List<Path> filesUnder(String rel, String ext) throws IOException {
        Path dir = moduleRoot().resolve(rel);
        assertTrue(Files.isDirectory(dir), dir + " 없음");
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(ext)).sorted().toList();
        }
    }

    /** shared-common 코드 소스 위치에서 위로 올라가 src/main/java 를 가진 모듈 루트. */
    private static Path moduleRoot() {
        try {
            Path p = Path.of(OutboxJson.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isDirectory(p)) {
                p = p.getParent();
            }
            while (p != null && !Files.isDirectory(p.resolve("src/main/java"))) {
                p = p.getParent();
            }
            if (p == null) {
                throw new IllegalStateException("모듈 루트 탐색 실패");
            }
            return p;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
