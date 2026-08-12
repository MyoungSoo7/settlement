package github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.spec;

import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepField;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepFieldType;
import github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol.FepProtocolException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 전문 스펙 로더 — YAML 스펙 파일을 읽어 검증된 {@link TelegramCatalog} 을 만든다 (ADR 0033 Phase 1).
 *
 * <p>스펙 위반은 <b>로딩 시점에 전부 실패</b>시킨다. 잘못된 스펙이 살아남아 런타임에 어긋난 전문을
 * 만들어내는 것보다, 기동 자체가 실패하는 편이 대외 전송 경계에서 언제나 낫다.
 *
 * <p><b>알 수 없는 키는 거부한다.</b> {@code lenght: 5} 같은 오타를 무시하고 넘어가면 그 필드가
 * 통째로 빠진 전문이 만들어져 이후 모든 필드의 byte offset 이 밀린다 — 조용한 금액 사고의 전형이다.
 */
public final class TelegramSpecLoader {

    /** 펌뱅킹 전문 스펙 위치 (classpath). */
    public static final String FIRMBANKING_LOCATION = "telegram/firmbanking";

    private static final Set<String> TELEGRAM_KEYS =
            Set.of("telegram", "msgType", "description", "version", "effectiveFrom", "include", "totalLength", "fields");
    private static final Set<String> FRAGMENT_KEYS = Set.of("fragment", "description", "fields");
    private static final Set<String> FIELD_KEYS = Set.of("name", "length", "type", "scale");
    private static final Set<String> OCCURS_KEYS = Set.of("name", "count", "countField", "max", "fields");

    private TelegramSpecLoader() {
    }

    /** classpath 디렉터리의 모든 {@code *.yaml} 을 읽어 카탈로그를 만든다. */
    public static TelegramCatalog loadFromClasspath(String location) {
        Map<String, String> sources = new LinkedHashMap<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:" + location + "/*.yaml");
            // 파일명 정렬 — 로딩 순서가 환경에 따라 달라지면 중복 진단 메시지가 흔들린다.
            Arrays.sort(resources, Comparator.comparing(r -> String.valueOf(r.getFilename())));
            for (Resource resource : resources) {
                try (InputStream in = resource.getInputStream()) {
                    sources.put(String.valueOf(resource.getFilename()),
                            new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            throw new FepProtocolException("전문 스펙 로딩 실패: " + location, e);
        }
        if (sources.isEmpty()) {
            throw new FepProtocolException("전문 스펙이 하나도 없다: classpath:" + location);
        }
        return parseAll(sources);
    }

    /**
     * 스펙 원문 묶음을 파싱한다 (파일명 → YAML 본문). 파일시스템 없이 검증 규칙을 시험할 수 있는 진입점.
     *
     * <p>2-pass — fragment 를 먼저 모은 뒤 전문을 조립한다. 파일 순서에 의존하지 않기 위해서다.
     */
    public static TelegramCatalog parseAll(Map<String, String> sources) {
        Map<String, List<TelegramElement>> fragments = new LinkedHashMap<>();
        Map<String, Map<String, Object>> telegrams = new LinkedHashMap<>();

        sources.forEach((file, content) -> {
            Map<String, Object> root = readYaml(file, content);
            if (root.containsKey("fragment")) {
                requireKnownKeys(file, root.keySet(), FRAGMENT_KEYS);
                String name = requireText(file, root, "fragment");
                if (fragments.put(name, readElements(file, root)) != null) {
                    throw new FepProtocolException("fragment 중복: " + name + " (" + file + ")");
                }
            } else if (root.containsKey("telegram")) {
                requireKnownKeys(file, root.keySet(), TELEGRAM_KEYS);
                telegrams.put(file, root);
            } else {
                throw new FepProtocolException("telegram 또는 fragment 키 필수: " + file);
            }
        });

        List<TelegramSpec> specs = new ArrayList<>();
        telegrams.forEach((file, root) -> specs.add(toSpec(file, root, fragments)));
        return TelegramCatalog.of(specs);
    }

    private static TelegramSpec toSpec(String file, Map<String, Object> root, Map<String, List<TelegramElement>> fragments) {
        String name = requireText(file, root, "telegram");

        List<TelegramElement> elements = new ArrayList<>();
        Object include = root.get("include");
        if (include != null) {
            List<TelegramElement> fragment = fragments.get(String.valueOf(include));
            if (fragment == null) {
                throw new FepProtocolException(
                        "존재하지 않는 fragment include: " + include + " (" + file + ", 등록: " + fragments.keySet() + ")");
            }
            elements.addAll(fragment);
        }
        elements.addAll(readElements(file, root));

        TelegramSpec spec = new TelegramSpec(
                name,
                requireQuotedCode(file, root),
                root.containsKey("description") ? String.valueOf(root.get("description")) : "",
                root.containsKey("version") ? requireInt(file, root.get("version"), "version") : 1,
                readEffectiveFrom(file, root.get("effectiveFrom")),
                elements);

        if (root.containsKey("totalLength")) {
            if (spec.isVariable()) {
                throw new FepProtocolException("가변 전문에는 totalLength 를 선언할 수 없다(건수마다 달라진다): "
                        + name + " (" + file + ") — 반복부 max 로 상한을 표현한다");
            }
            int declared = requireInt(file, root.get("totalLength"), "totalLength");
            if (declared != spec.totalLength()) {
                throw new FepProtocolException("전문 총길이 불일치: " + name + " 선언 " + declared
                        + " != 계산 " + spec.totalLength() + " 바이트 (" + file + ")");
            }
        }
        return spec;
    }

    /** 본문 구성 요소 목록 — 단일 필드와 반복부(occurs)가 선언 순서대로 섞일 수 있다. */
    @SuppressWarnings("unchecked")
    private static List<TelegramElement> readElements(String file, Map<String, Object> root) {
        Object raw = root.get("fields");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new FepProtocolException("fields 목록 필수: " + file);
        }
        List<TelegramElement> elements = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                throw new FepProtocolException("fields 항목은 매핑이어야 한다: " + file + " → " + entry);
            }
            Map<String, Object> element = (Map<String, Object>) map;
            if (element.containsKey("occurs")) {
                elements.add(readOccurs(file, element));
            } else {
                elements.add(new TelegramElement.Single(readField(file, element)));
            }
        }
        return elements;
    }

    @SuppressWarnings("unchecked")
    private static TelegramElement readOccurs(String file, Map<String, Object> element) {
        if (element.size() > 1) {
            throw new FepProtocolException("occurs 항목에는 occurs 키만 둔다: " + file + " → " + element.keySet());
        }
        if (!(element.get("occurs") instanceof Map<?, ?> raw)) {
            throw new FepProtocolException("occurs 는 매핑이어야 한다: " + file);
        }
        Map<String, Object> occurs = new LinkedHashMap<>();
        raw.forEach((key, value) -> occurs.put(String.valueOf(key), value));
        requireKnownKeys(file, occurs.keySet(), OCCURS_KEYS);

        String name = requireText(file, occurs, "name");
        boolean fixed = occurs.containsKey("count");
        boolean variable = occurs.containsKey("countField");
        if (fixed == variable) {
            throw new FepProtocolException("반복부는 count(고정) 또는 countField(가변) 중 정확히 하나를 쓴다: "
                    + file + "." + name);
        }
        Object rawFields = occurs.get("fields");
        if (!(rawFields instanceof List<?> list) || list.isEmpty()) {
            throw new FepProtocolException("반복부 fields 목록 필수: " + file + "." + name);
        }
        List<FepField> fields = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                throw new FepProtocolException("반복부 fields 항목은 매핑이어야 한다: " + file + "." + name);
            }
            Map<String, Object> field = (Map<String, Object>) map;
            if (field.containsKey("occurs")) {
                throw new FepProtocolException("반복부 중첩은 지원하지 않는다: " + file + "." + name);
            }
            fields.add(readField(file, field));
        }
        if (fixed) {
            return new TelegramElement.Repeated(
                    name, requireInt(file, occurs.get("count"), "count(" + name + ")"), fields);
        }
        return new TelegramElement.VariableRepeated(
                name,
                requireText(file, occurs, "countField"),
                requireInt(file, occurs.get("max"), "max(" + name + ")"),
                fields);
    }

    private static FepField readField(String file, Map<String, Object> field) {
        requireKnownKeys(file, field.keySet(), FIELD_KEYS);
        String fieldName = requireText(file, field, "name");
        int length = requireInt(file, field.get("length"), "length(" + fieldName + ")");
        Integer scale = field.containsKey("scale")
                ? requireInt(file, field.get("scale"), "scale(" + fieldName + ")")
                : null;
        return new FepField(fieldName, length, readType(file, fieldName, field.get("type")), scale);
    }

    private static FepFieldType readType(String file, String fieldName, Object raw) {
        if (raw == null) throw new FepProtocolException("필드 타입 필수: " + file + "." + fieldName);
        try {
            return FepFieldType.valueOf(String.valueOf(raw));
        } catch (IllegalArgumentException e) {
            throw new FepProtocolException("알 수 없는 필드 타입: " + raw + " (" + file + "." + fieldName
                    + ", 허용: " + Arrays.toString(FepFieldType.values()) + ")", e);
        }
    }

    /**
     * 전문구분코드는 <b>반드시 인용부호</b>로 감싼 문자열이어야 한다.
     * {@code msgType: 0200} 은 YAML 이 숫자 200 으로 읽어 선행 0 이 사라진다 — 전문이 통째로 어긋난다.
     */
    private static String requireQuotedCode(String file, Map<String, Object> root) {
        Object raw = root.get("msgType");
        if (raw == null) throw new FepProtocolException("msgType 필수: " + file);
        if (!(raw instanceof String code)) {
            throw new FepProtocolException("msgType 은 인용부호로 감싼 문자열이어야 한다(선행 0 유실 방지): "
                    + file + " → msgType: \"" + raw + "\"");
        }
        return code;
    }

    private static LocalDate readEffectiveFrom(String file, Object raw) {
        return switch (raw) {
            case null -> null;
            // SnakeYAML 은 인용부호 없는 2026-01-01 을 timestamp(Date)로 해석한다.
            case Date date -> date.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
            case String text -> parseDate(file, text);
            default -> throw new FepProtocolException("effectiveFrom 형식 오류(yyyy-MM-dd): " + file + " → " + raw);
        };
    }

    private static LocalDate parseDate(String file, String text) {
        try {
            return LocalDate.parse(text);
        } catch (RuntimeException e) {
            throw new FepProtocolException("effectiveFrom 형식 오류(yyyy-MM-dd): " + file + " → " + text, e);
        }
    }

    private static Map<String, Object> readYaml(String file, String content) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object root = new Yaml(new SafeConstructor(options)).load(content);
        if (!(root instanceof Map<?, ?> map)) {
            throw new FepProtocolException("스펙 파일이 매핑이 아니다: " + file);
        }
        Map<String, Object> typed = new LinkedHashMap<>();
        map.forEach((key, value) -> typed.put(String.valueOf(key), value));
        return typed;
    }

    private static void requireKnownKeys(String file, Set<String> actual, Set<String> allowed) {
        for (String key : actual) {
            if (!allowed.contains(key)) {
                throw new FepProtocolException(
                        "알 수 없는 키: " + key + " (" + file + ", 허용: " + allowed.stream().sorted().toList() + ")");
            }
        }
    }

    private static String requireText(String file, Map<String, Object> map, String key) {
        Object raw = map.get(key);
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new FepProtocolException(key + " 필수: " + file);
        }
        return String.valueOf(raw);
    }

    private static int requireInt(String file, Object raw, String key) {
        if (!(raw instanceof Integer value)) {
            throw new FepProtocolException(key + " 은 정수여야 한다: " + file + " → " + raw);
        }
        return value;
    }
}
